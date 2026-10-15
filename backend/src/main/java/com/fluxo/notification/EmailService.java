package com.fluxo.notification;

import com.fluxo.ticket.Ticket;
import com.fluxo.user.User;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Email notifications (spec §9, V1): only when SMTP is configured ({@code spring.mail.host}). Lines are queued and sent
 * as one email per user and ticket once the first line is 2 minutes old; emails of a ticket share thread headers.
 */
@Service
public class EmailService {

    static final Duration WINDOW = Duration.ofMinutes(2);
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final ObjectProvider<JavaMailSender> mail;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final String from;
    private final String baseUrl;

    EmailService(ObjectProvider<JavaMailSender> mail, JdbcClient jdbc, Clock clock,
                 @Value("${fluxo.mail.from:fluxo@localhost}") String from, @Value("${fluxo.base-url:http://localhost:8080}") String baseUrl) {
        this.mail = mail;
        this.jdbc = jdbc;
        this.clock = clock;
        this.from = from;
        this.baseUrl = baseUrl.replaceAll("/$", "");
    }

    public boolean enabled() {
        return mail.getIfAvailable() != null;
    }

    /** Queues one line for the user's next email about this ticket. */
    void enqueue(User user, Ticket t, String line) {
        if (!enabled() || user.getEmail() == null || user.getEmail().isBlank()) return;
        jdbc.sql("insert into email_outbox (id, user_id, ticket_id, ticket_key, summary, line) values (?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), user.getId(), t.getId(), t.getKey(), t.getSummary(), line).update();
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    void flushDue() {
        if (enabled()) flush(clock.instant().minus(WINDOW));
    }

    record Pending(UUID id, UUID userId, String email, UUID ticketId, String key, String summary, String line) {}

    /** Sends every group whose first line is older than {@code cutoff}; returns how many emails were sent. */
    public int flush(Instant cutoff) {
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null) return 0;
        List<Pending> pending = jdbc.sql("""
                        select o.id, o.user_id, u.email, o.ticket_id, o.ticket_key, o.summary, o.line from email_outbox o
                        join users u on u.id = o.user_id
                        where o.sent_at is null and (o.user_id, o.ticket_key) in (
                            select user_id, ticket_key from email_outbox where sent_at is null group by user_id, ticket_key having min(created_at) <= ?)
                        order by o.created_at
                        """).param(java.sql.Timestamp.from(cutoff))
                .query((rs, i) -> new Pending(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("email"),
                        rs.getObject("ticket_id", UUID.class), rs.getString("ticket_key"), rs.getString("summary"), rs.getString("line"))).list();
        Map<String, List<Pending>> groups = new LinkedHashMap<>();
        pending.forEach(p -> groups.computeIfAbsent(p.userId() + "|" + p.key(), k -> new ArrayList<>()).add(p));
        int sent = 0;
        for (List<Pending> g : groups.values()) {
            Pending first = g.getFirst();
            try {
                MimeMessage m = sender.createMimeMessage();
                MimeMessageHelper h = new MimeMessageHelper(m, false, "UTF-8");
                h.setFrom(from);
                h.setTo(first.email());
                h.setSubject("[" + first.key() + "] " + g.getLast().summary());
                StringBuilder body = new StringBuilder();
                g.forEach(p -> body.append(p.line()).append("\n\n"));
                body.append(baseUrl).append("/browse/").append(first.key()).append('\n');
                h.setText(body.toString());
                // One thread per ticket in mail clients (spec §9).
                String thread = "<ticket-" + (first.ticketId() == null ? first.key() : first.ticketId()) + "@fluxo>";
                m.setHeader("In-Reply-To", thread);
                m.setHeader("References", thread);
                m.setHeader("Message-ID", "<" + UUID.randomUUID() + "@fluxo>");
                sender.send(m);
                sent++;
            } catch (MessagingException | MailException e) {
                log.warn("Email to {} about {} failed: {}", first.email(), first.key(), e.getMessage());
                continue; // stays queued, retried on the next run
            }
            jdbc.sql("update email_outbox set sent_at = now() where id in (:ids)").param("ids", g.stream().map(Pending::id).toList()).update();
        }
        return sent;
    }
}
