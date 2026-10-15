package com.fluxo.ticket;

import com.fluxo.field.FieldService;
import com.fluxo.collab.CommentService;
import com.fluxo.workflow.Transition;
import com.fluxo.workflow.Workflow;
import com.fluxo.workflow.WorkflowService;
import com.fluxo.workflow.WorkflowWebhook;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.node.NullNode;

import com.fluxo.auth.AuthUser;
import com.fluxo.collab.WatcherService;
import com.fluxo.notification.TicketEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.fluxo.common.AuditService;
import com.fluxo.common.Errors;
import com.fluxo.space.Perm;
import com.fluxo.space.PermissionService;
import com.fluxo.space.Space;
import com.fluxo.space.SpaceRepository;
import com.fluxo.user.User;
import com.fluxo.user.UserRepository;
import com.fluxo.workflow.Status;
import com.fluxo.workflow.StatusCategory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Ticket business rules (spec §4.3, §4.8, §4.9). Every change goes through {@link #apply}, so
 * validation and history are written in one place for create, edit and parent changes alike.
 */
@Service
@Transactional
public class TicketService {

    public static final int MAX_DESCRIPTION = 100_000;
    private static final Pattern KEY = Pattern.compile("([A-Z][A-Z0-9]{1,9})-(\\d+)");
    private static final Pattern LABEL = Pattern.compile("[^\\s,]{1,64}");
    static final Set<String> FIELDS = Set.of("typeId", "summary", "description", "priorityId", "assigneeId",
            "reporterId", "parent", "dueDate", "startDate", "estimate", "labels", "flagged", "componentIds", "fixVersionIds", "customFields", "collaboratorIds", "archived");

    private final TicketRepository tickets;
    private final SpaceRepository spaces;
    private final PermissionService permissions;
    private final TicketTypeRepository types;
    private final PriorityRepository priorities;
    private final ResolutionRepository resolutions;
    private final UserRepository users;
    private final LabelRepository labels;
    private final HistoryRepository history;
    private final FieldDefaultRepository defaults;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;
    private final WatcherService watchers;
    private final ApplicationEventPublisher events;
    private final WorkflowService workflow;
    private final ObjectProvider<CommentService> comments;
    private final ComponentRepository components;
    private final FixVersionRepository versions;
    private final FieldService fieldService;

    TicketService(TicketRepository tickets, SpaceRepository spaces, PermissionService permissions,
                  TicketTypeRepository types, PriorityRepository priorities, ResolutionRepository resolutions,
                  UserRepository users, LabelRepository labels, HistoryRepository history,
                  FieldDefaultRepository defaults, AuditService audit, JsonMapper json, Clock clock,
                  WatcherService watchers, ApplicationEventPublisher events, WorkflowService workflow,
                  ObjectProvider<CommentService> comments, ComponentRepository components, FixVersionRepository versions,
                  FieldService fields) {
        this.fieldService = fields;
        this.components = components;
        this.versions = versions;
        this.workflow = workflow;
        this.comments = comments; // lazy: CommentService itself depends on this service
        this.tickets = tickets;
        this.spaces = spaces;
        this.permissions = permissions;
        this.types = types;
        this.priorities = priorities;
        this.resolutions = resolutions;
        this.users = users;
        this.labels = labels;
        this.history = history;
        this.defaults = defaults;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.watchers = watchers;
        this.events = events;
    }

    // --- Read ---------------------------------------------------------------

    /** Resolves a key (current, renamed space, or moved ticket) and checks BROWSE; 404 if invisible. */
    @Transactional(readOnly = true)
    public Ticket get(AuthUser me, String key) {
        Ticket t = find(key).orElseThrow(() -> Errors.notFound("Ticket introuvable"));
        if (!permissions.has(me, t.getSpace(), Perm.BROWSE)) throw Errors.notFound("Ticket introuvable");
        return t;
    }

    Optional<Ticket> find(String key) {
        var m = KEY.matcher(key == null ? "" : key.toUpperCase());
        if (!m.matches()) return Optional.empty();
        int number = Integer.parseInt(m.group(2));
        return tickets.findByKey(m.group(1), number)
                .or(() -> spaces.findByAlias(m.group(1)).flatMap(s -> tickets.findByKey(s.getKey(), number)))
                .or(() -> tickets.findIdByAlias(key.toUpperCase()).flatMap(tickets::findById));
    }

    // --- Create -------------------------------------------------------------

    public Ticket create(AuthUser me, Space space, JsonNode body) {
        permissions.check(me, space, Perm.CREATE);
        checkKnownFields(body);
        TicketType type = body.hasNonNull("typeId") ? type(space, body.get("typeId").asString()) : null;
        ObjectNode values = defaultsFor(space, type);
        body.properties().forEach(e -> values.set(e.getKey(), e.getValue()));
        if (type == null) type = values.hasNonNull("typeId") ? type(space, values.get("typeId").asString()) : defaultType(space);
        values.remove("typeId");
        // Defaults of custom fields come from the field configuration (spec §4.14).
        List<FieldService.LayoutField> layout = fieldService.layout(space, type);
        ObjectNode custom = values.has("customFields") && values.get("customFields").isObject()
                ? (ObjectNode) values.get("customFields") : json.createObjectNode();
        layout.stream().filter(l -> l.field().startsWith("cf:") && l.defaultValue() != null && !custom.has(l.field().substring(3)))
                .forEach(l -> custom.set(l.field().substring(3), json.valueToTree(l.defaultValue())));
        if (!custom.isEmpty()) values.set("customFields", custom);

        String summary = summary(values.get("summary"));
        Space locked = spaces.lockById(space.getId()).orElseThrow();
        User creator = users.getReferenceById(me.id());
        RefData.Priority priority = priorities.findFirstByIsDefaultTrue().orElseThrow();
        String rank = Rank.between(tickets.maxRank(space.getId()).orElse(null), null);
        Ticket t = new Ticket(locked, locked.takeNextNumber(), type, space.workflowFor(type).initialStatus(), priority,
                summary, creator, creator, rank);
        values.remove("summary");
        values.properties().forEach(e -> apply(me, t, e.getKey(), e.getValue(), null));
        validateParent(t, t.getType(), t.getParent());
        fieldService.checkRequired(t, layout);
        if (t.getAssignee() == null && !values.has("assigneeId")) {
            // The lead of a component becomes the default assignee (spec §4.13).
            t.getComponents().stream().map(Component::getLead).filter(Objects::nonNull)
                    .filter(u -> permissions.isAssignable(space, u.getId())).findFirst().ifPresent(t::setAssignee);
        }
        tickets.saveAndFlush(t); // the watchers row below is written with plain SQL
        history.save(new HistoryEntry(t, creator, "created", null, null, null, null));
        watchers.watch(t.getId(), me.id()); // the creator watches by default (spec §4.3)
        t.getCollaborators().forEach(u -> watchers.watch(t.getId(), u.getId()));
        events.publishEvent(TicketEvent.of(TicketEvent.Type.CREATED, t, me.id()));
        return t;
    }

    // --- Update -------------------------------------------------------------

    public Ticket update(AuthUser me, String key, JsonNode body) {
        Ticket t = get(me, key);
        permissions.check(me, t.getSpace(), Perm.EDIT);
        checkVersion(t, body);
        checkKnownFields(body);
        User previousAssignee = t.getAssignee();
        List<HistoryEntry> changes = new ArrayList<>();
        body.properties().forEach(e -> {
            if (!e.getKey().equals("version")) apply(me, t, e.getKey(), e.getValue(), changes);
        });
        watchers.autoWatch(t.getId(), me.id());
        if (!changes.isEmpty()) {
            boolean reassigned = changes.stream().anyMatch(h -> h.getField().equals("assignee"));
            events.publishEvent(new TicketEvent(reassigned ? TicketEvent.Type.ASSIGNED : TicketEvent.Type.UPDATED, t, me.id(),
                    TicketEvent.Change.of(changes), null, Set.of(), previousAssignee));
        }
        return t;
    }

    /** Validates and sets one field; writes a history entry when the value changes. */
    private void apply(AuthUser me, Ticket t, String field, JsonNode v, List<HistoryEntry> sink) {
        boolean clear = v == null || v.isNull();
        switch (field) {
            case "summary" -> change(me, t, field, t.getSummary(), summary(v), Function.identity(), t::setSummary, sink);
            case "description" -> {
                String d = clear || v.asString().isBlank() ? null : v.asString();
                if (d != null && d.length() > MAX_DESCRIPTION) {
                    throw Errors.badRequest("DESCRIPTION_TOO_LONG", "Description limitée à " + MAX_DESCRIPTION + " caractères");
                }
                change(me, t, field, t.getDescription(), d, s -> null, t::setDescription, sink);
            }
            case "priorityId" -> {
                RefData.Priority p = clear ? priorities.findFirstByIsDefaultTrue().orElseThrow()
                        : priorities.findById(uuid(v)).orElseThrow(() -> Errors.badRequest("INVALID_PRIORITY", "Priorité inconnue"));
                change(me, t, "priority", t.getPriority(), p, RefData.Priority::getName, t::setPriority, sink);
            }
            case "assigneeId" -> {
                User u = clear ? null : users.findById(uuid(v)).orElse(null);
                if (!clear) {
                    if (u == null || !u.isActive() || !permissions.isAssignable(t.getSpace(), u.getId())) {
                        throw Errors.badRequest("NOT_ASSIGNABLE", "Cette personne ne peut pas être assignée dans cet espace");
                    }
                    if (sink != null && !me.id().equals(u.getId())) permissions.check(me, t.getSpace(), Perm.ASSIGN);
                }
                if (sink != null && clear) permissions.check(me, t.getSpace(), Perm.ASSIGN);
                change(me, t, "assignee", t.getAssignee(), u, User::getDisplayName, t::setAssignee, sink);
            }
            case "reporterId" -> {
                if (clear) throw Errors.badRequest("REPORTER_REQUIRED", "Le rapporteur est obligatoire");
                User u = users.findById(uuid(v)).filter(User::isActive)
                        .orElseThrow(() -> Errors.badRequest("INVALID_USER", "Utilisateur introuvable"));
                change(me, t, "reporter", t.getReporter(), u, User::getDisplayName, t::setReporter, sink);
            }
            case "parent" -> {
                Ticket parent = clear ? null : find(v.asString())
                        .filter(p -> permissions.has(me, p.getSpace(), Perm.BROWSE))
                        .orElseThrow(() -> Errors.badRequest("INVALID_PARENT", "Ticket parent introuvable"));
                if (sink != null) validateParent(t, t.getType(), parent);
                change(me, t, field, t.getParent(), parent, Ticket::getKey, t::setParent, sink);
            }
            case "dueDate" -> change(me, t, field, t.getDueDate(), date(v), LocalDate::toString, t::setDueDate, sink);
            case "startDate" -> change(me, t, field, t.getStartDate(), date(v), LocalDate::toString, t::setStartDate, sink);
            case "estimate" -> {
                BigDecimal e = clear ? null : v.isNumber() ? v.decimalValue() : null;
                if (!clear && (e == null || e.signum() < 0)) throw Errors.badRequest("INVALID_ESTIMATE", "Estimation invalide");
                change(me, t, field, t.getEstimate(), e, BigDecimal::toPlainString, t::setEstimate, sink);
            }
            case "flagged" -> change(me, t, field, t.isFlagged(), !clear && v.asBoolean(), b -> b ? "Signalé" : "Non signalé", t::setFlagged, sink);
            case "labels" -> setLabels(me, t, clear ? List.of() : stringList(v), sink);
            case "customFields" -> setCustomFields(me, t, v, sink);
            case "archived" -> change(me, t, field, t.isArchived(), !clear && v.asBoolean(), x -> x ? "Archivé" : "Actif", t::setArchived, sink);
            case "collaboratorIds" -> {
                List<User> next = (clear ? List.<UUID>of() : uuids(v)).stream().map(id -> users.findById(id).filter(User::isActive)
                        .filter(u -> permissions.has(AuthUser.of(u), t.getSpace(), Perm.BROWSE))
                        .orElseThrow(() -> Errors.badRequest("INVALID_COLLABORATOR", "Collaborateur inconnu ou sans accès à l'espace"))).toList();
                setSet(me, t, "collaborators", t.getCollaborators(), next, User::getDisplayName, sink);
                if (t.getId() != null) next.forEach(u -> watchers.watch(t.getId(), u.getId())); // collaborators follow the ticket
            }
            case "componentIds" -> setSet(me, t, "components", t.getComponents(), clear ? List.of() : uuids(v).stream().map(id -> {
                Component c = components.findById(id).filter(x -> x.getSpace().equals(t.getSpace()))
                        .orElseThrow(() -> Errors.badRequest("INVALID_COMPONENT", "Composant inconnu dans cet espace"));
                if (c.isArchived() && !t.getComponents().contains(c)) throw Errors.badRequest("INVALID_COMPONENT", "Composant archivé : " + c.getName());
                return c;
            }).toList(), Component::getName, sink);
            case "fixVersionIds" -> setSet(me, t, "fixVersions", t.getFixVersions(), clear ? List.of() : uuids(v).stream().map(id -> {
                FixVersion f = versions.findById(id).filter(x -> x.getSpace().equals(t.getSpace()))
                        .orElseThrow(() -> Errors.badRequest("INVALID_VERSION", "Version inconnue dans cet espace"));
                if (f.getStatus() == FixVersion.Status.ARCHIVED && !t.getFixVersions().contains(f)) throw Errors.badRequest("INVALID_VERSION", "Version archivée : " + f.getName());
                return f;
            }).toList(), FixVersion::getName, sink);
            case "typeId" -> {
                if (clear) throw Errors.badRequest("TYPE_REQUIRED", "Le type est obligatoire");
                changeType(me, t, type(t.getSpace(), v.asString()), sink);
            }
            default -> throw Errors.badRequest("UNKNOWN_FIELD", "Champ inconnu : " + field);
        }
    }

    private <T> void change(AuthUser me, Ticket t, String field, T old, T value, Function<T, String> display,
                            java.util.function.Consumer<T> setter, List<HistoryEntry> sink) {
        if (Objects.equals(old, value)) return;
        setter.accept(value);
        if (sink != null) {
            sink.add(history.save(new HistoryEntry(t, users.getReferenceById(me.id()), field, raw(old), raw(value),
                    old == null ? null : display.apply(old), value == null ? null : display.apply(value))));
        }
    }

    private static String raw(Object v) {
        if (v == null) return null;
        if (v instanceof User u) return u.getId().toString();
        if (v instanceof Ticket t) return t.getId().toString();
        if (v instanceof RefData.Priority p) return p.getId().toString();
        if (v instanceof TicketType ty) return ty.getId().toString();
        if (v instanceof Status s) return s.getId().toString();
        if (v instanceof RefData.Resolution r) return r.getId().toString();
        return v.toString();
    }

    private void setLabels(AuthUser me, Ticket t, List<String> names, List<HistoryEntry> sink) {
        Set<Label> next = new LinkedHashSet<>();
        for (String raw : names) {
            String name = raw.strip();
            if (!LABEL.matcher(name).matches()) {
                throw Errors.badRequest("INVALID_LABEL", "Label invalide « " + raw + " » : sans espace, 64 caractères max.");
            }
            next.add(labels.findByName(t.getSpace().getId(), name).orElseGet(() -> labels.save(new Label(t.getSpace(), name))));
        }
        String before = labelNames(t.getLabels()), after = labelNames(next);
        if (before.equals(after)) return;
        t.getLabels().clear();
        t.getLabels().addAll(next);
        if (sink != null) {
            sink.add(history.save(new HistoryEntry(t, users.getReferenceById(me.id()), "labels", before, after,
                    before.isEmpty() ? null : before, after.isEmpty() ? null : after)));
        }
    }

    /** Custom field values (spec §4.14): only fields enabled for the ticket's type; one history entry per field. */
    private void setCustomFields(AuthUser me, Ticket t, JsonNode v, List<HistoryEntry> sink) {
        if (v == null || !v.isObject()) throw Errors.badRequest("INVALID_FIELD_VALUE", "Objet { idDuChamp: valeur } attendu");
        List<FieldService.CustomField> allowed = fieldService.enabledFor(t.getSpace(), t.getType());
        Map<String, Object> next = new HashMap<>(t.getCustomValues());
        v.properties().forEach(e -> {
            FieldService.CustomField f = allowed.stream().filter(x -> x.id().toString().equals(e.getKey())).findFirst()
                    .orElseThrow(() -> Errors.badRequest("UNKNOWN_FIELD", "Champ non disponible pour ce type de ticket"));
            Object value = fieldService.normalize(f, e.getValue());
            Object old = next.get(e.getKey());
            if (Objects.equals(String.valueOf(old), String.valueOf(value))) return;
            if (value == null) next.remove(e.getKey());
            else next.put(e.getKey(), value);
            if (sink != null) {
                sink.add(history.save(new HistoryEntry(t, users.getReferenceById(me.id()), f.name(),
                        old == null ? null : String.valueOf(old), value == null ? null : String.valueOf(value),
                        fieldService.display(f, old), fieldService.display(f, value))));
            }
        });
        t.setCustomValues(next);
    }

    /** Replaces a set of references (components, versions) and records the change by names. */
    private <T> void setSet(AuthUser me, Ticket t, String field, Set<T> current, List<T> next, Function<T, String> name,
                            List<HistoryEntry> sink) {
        Function<Collection<T>, String> names = c -> c.stream().map(name).sorted(String.CASE_INSENSITIVE_ORDER).reduce((a, b) -> a + ", " + b).orElse("");
        String before = names.apply(current), after = names.apply(next);
        if (before.equals(after)) return;
        current.clear();
        current.addAll(next);
        if (sink != null) {
            sink.add(history.save(new HistoryEntry(t, users.getReferenceById(me.id()), field, before, after,
                    before.isEmpty() ? null : before, after.isEmpty() ? null : after)));
        }
    }

    private static List<UUID> uuids(JsonNode v) {
        if (!v.isArray()) throw Errors.badRequest("INVALID_IDS", "Liste d'identifiants attendue");
        List<UUID> out = new ArrayList<>();
        try {
            v.forEach(n -> out.add(UUID.fromString(n.asString())));
        } catch (IllegalArgumentException e) {
            throw Errors.badRequest("INVALID_IDS", "Identifiant invalide");
        }
        return out;
    }

    private static String labelNames(Collection<Label> ls) {
        return ls.stream().map(Label::getName).sorted(String.CASE_INSENSITIVE_ORDER).reduce((a, b) -> a + " " + b).orElse("");
    }

    private void changeType(AuthUser me, Ticket t, TicketType type, List<HistoryEntry> sink) {
        if (type.getHierarchyLevel() != t.getType().getHierarchyLevel()) {
            for (Ticket child : tickets.findChildren(t.getId())) {
                if (child.getType().getHierarchyLevel() != type.getHierarchyLevel() - 1) {
                    throw Errors.badRequest("INVALID_TYPE_CHANGE", "Impossible : les enfants de ce ticket ne sont pas compatibles avec le type " + type.getName());
                }
            }
            validateParent(t, type, t.getParent());
        }
        change(me, t, "type", t.getType(), type, TicketType::getName, t::setType, sink);
        // Another type may have another workflow: keep the same-named status, else start over.
        Workflow wf = t.getSpace().workflowFor(type);
        if (!wf.getStatuses().contains(t.getStatus())) {
            Status s = wf.getStatuses().stream().filter(x -> x.getName().equalsIgnoreCase(t.getStatus().getName())).findFirst()
                    .orElse(wf.initialStatus());
            change(me, t, "status", t.getStatus(), s, Status::getName, t::setStatus, sink);
        }
    }

    /** Epic (1) > standard (0) > subtask (-1): a parent is exactly one level up; subtasks stay in their parent's space. */
    private void validateParent(Ticket t, TicketType type, Ticket parent) {
        int level = type.getHierarchyLevel();
        if (parent == null) {
            if (level == -1) throw Errors.badRequest("PARENT_REQUIRED", "Une sous-tâche doit avoir un parent");
            return;
        }
        if (parent.getId() != null && parent.getId().equals(t.getId())) {
            throw Errors.badRequest("INVALID_PARENT", "Un ticket ne peut pas être son propre parent");
        }
        if (parent.getType().getHierarchyLevel() != level + 1) {
            throw Errors.badRequest("INVALID_PARENT", level == 1 ? "Un epic ne peut pas avoir de parent"
                    : level == 0 ? "Le parent d'un ticket standard doit être un epic"
                    : "Le parent d'une sous-tâche doit être un ticket standard");
        }
        if (level == -1 && !parent.getSpace().getId().equals(t.getSpace().getId())) {
            throw Errors.badRequest("INVALID_PARENT", "Une sous-tâche doit rester dans l'espace de son parent");
        }
    }

    // --- Transitions ----------------------------------------------------------

    public Optional<UUID> priorityByName(String name) {
        return priorities.findByNameIgnoreCase(name.strip()).map(RefData.Priority::getId);
    }

    public Ticket transition(AuthUser me, String key, UUID statusId, UUID resolutionId, Integer version) {
        return transition(me, key, statusId, resolutionId, version, null);
    }

    /**
     * Moves the ticket to {@code statusId} through an allowed transition of its workflow (spec §4.9): conditions,
     * validators (required fields, required comment), then post-transition actions; the screen's comment is added.
     */
    public Ticket transition(AuthUser me, String key, UUID statusId, UUID resolutionId, Integer version, String comment) {
        Ticket t = get(me, key);
        permissions.check(me, t.getSpace(), Perm.TRANSITION);
        if (version != null && version != t.getVersion()) throw conflict(t);
        Status target = t.getSpace().workflowFor(t.getType()).getStatuses().stream().filter(s -> s.getId().equals(statusId))
                .findFirst().orElseThrow(() -> Errors.badRequest("INVALID_STATUS", "Statut inconnu dans ce workflow"));
        if (target.getId().equals(t.getStatus().getId())) return t;
        WorkflowService.Option way = workflow.resolve(me, t, target);
        workflow.validate(t, way, comment);
        watchers.autoWatch(t.getId(), me.id());
        boolean wasDone = t.isDone();
        List<HistoryEntry> changes = new ArrayList<>();
        change(me, t, "status", t.getStatus(), target, Status::getName, t::setStatus, changes);
        boolean isDone = target.getCategory() == StatusCategory.DONE;
        if (isDone && !wasDone) {
            RefData.Resolution r = resolutionId != null
                    ? resolutions.findById(resolutionId).orElseThrow(() -> Errors.badRequest("INVALID_RESOLUTION", "Résolution inconnue"))
                    : resolutions.findFirstByIsDefaultTrue().orElseThrow();
            change(me, t, "resolution", t.getResolution(), r, RefData.Resolution::getName, t::setResolution, changes);
            t.setResolvedAt(clock.instant());
        } else if (!isDone && wasDone) {
            change(me, t, "resolution", t.getResolution(), null, RefData.Resolution::getName, t::setResolution, changes);
            t.setResolvedAt(null);
        }
        runActions(me, t, way, changes);
        var type = isDone && !wasDone ? TicketEvent.Type.RESOLVED : !isDone && wasDone ? TicketEvent.Type.REOPENED : TicketEvent.Type.TRANSITIONED;
        events.publishEvent(new TicketEvent(type, t, me.id(), TicketEvent.Change.of(changes), null, Set.of(), null));
        if (comment != null && !comment.isBlank()) comments.getObject().add(me, t.getKey(), comment);
        return t;
    }

    /** Post-transition actions (spec §4.9); they write history like manual edits. */
    private void runActions(AuthUser me, Ticket t, WorkflowService.Option way, List<HistoryEntry> sink) {
        for (Transition.Rule a : way.rules().actions()) {
            switch (a.type()) {
                case "ASSIGN_ME" -> change(me, t, "assignee", t.getAssignee(), users.findById(me.id()).orElseThrow(), User::getDisplayName, t::setAssignee, sink);
                case "ASSIGN_REPORTER" -> change(me, t, "assignee", t.getAssignee(), t.getReporter(), User::getDisplayName, t::setAssignee, sink);
                case "UNASSIGN" -> change(me, t, "assignee", t.getAssignee(), null, User::getDisplayName, t::setAssignee, sink);
                case "CLEAR_FIELD" -> apply(me, t, a.field(), NullNode.getInstance(), sink);
                case "SET_FIELD" -> apply(me, t, a.field(), jsonValue(a.value()), sink);
                case "WEBHOOK" -> events.publishEvent(new WorkflowWebhook(a.value(), t.getKey(), way.name(), me.id()));
                default -> { }
            }
        }
    }

    /** Action values are JSON (`3`, `true`, `["a"]`, `"2026-12-01"`); bare text is taken as a string. */
    private JsonNode jsonValue(String value) {
        if (value == null) return NullNode.getInstance();
        try {
            return json.readTree(value);
        } catch (RuntimeException e) {
            return json.getNodeFactory().textNode(value);
        }
    }

    // --- Move and delete ------------------------------------------------------

    public record MovePreview(String newKeyPrefix, List<String> lostData, List<String> subtasksMoved) {}

    @Transactional(readOnly = true)
    public MovePreview previewMove(AuthUser me, String key, String targetKey) {
        Ticket t = get(me, key);
        Space target = targetSpace(me, t, targetKey);
        List<String> lost = new ArrayList<>();
        if (!target.getTicketTypes().contains(t.getType())) lost.add("Type « " + t.getType().getName() + " » non disponible : à choisir");
        boolean statusKept = target.workflowFor(t.getType()).getStatuses().stream().anyMatch(s -> s.getName().equalsIgnoreCase(t.getStatus().getName()));
        if (!statusKept) lost.add("Statut « " + t.getStatus().getName() + " » absent : le ticket repartira au statut initial");
        if (t.getAssignee() != null && !permissions.isAssignable(target, t.getAssignee().getId())) {
            lost.add("Assigné « " + t.getAssignee().getDisplayName() + " » non assignable dans l'espace cible : retiré");
        }
        if (!t.getComponents().isEmpty()) lost.add("Composants retirés : " + t.getComponents().stream().map(Component::getName).sorted().toList());
        if (!t.getFixVersions().isEmpty()) lost.add("Versions retirées : " + t.getFixVersions().stream().map(FixVersion::getName).sorted().toList());
        List<String> subtasks = tickets.findChildren(t.getId()).stream()
                .filter(c -> c.getType().getHierarchyLevel() == -1).map(Ticket::getKey).toList();
        return new MovePreview(target.getKey(), lost, subtasks);
    }

    public Ticket move(AuthUser me, String key, String targetKey, UUID typeId) {
        Ticket t = get(me, key);
        permissions.check(me, t.getSpace(), Perm.EDIT);
        if (t.getType().getHierarchyLevel() == -1) {
            throw Errors.badRequest("SUBTASK_MOVE", "Une sous-tâche se déplace avec son parent");
        }
        Space target = targetSpace(me, t, targetKey);
        TicketType type = typeId != null ? type(target, typeId.toString())
                : target.getTicketTypes().contains(t.getType()) ? t.getType() : null;
        if (type == null) throw Errors.badRequest("TYPE_REQUIRED", "Choisissez un type disponible dans l'espace cible");
        if (type.getHierarchyLevel() != t.getType().getHierarchyLevel()) {
            throw Errors.badRequest("INVALID_TYPE_CHANGE", "Le type cible doit être du même niveau");
        }
        Space locked = spaces.lockById(target.getId()).orElseThrow();
        String oldKey = t.getKey();
        moveOne(me, t, locked, type);
        events.publishEvent(new TicketEvent(TicketEvent.Type.MOVED, t, me.id(), List.of(new TicketEvent.Change("space", oldKey, t.getKey())), null, Set.of(), null));
        for (Ticket child : tickets.findChildren(t.getId())) {
            if (child.getType().getHierarchyLevel() == -1) moveOne(me, child, locked, child.getType());
        }
        return t;
    }

    private void moveOne(AuthUser me, Ticket t, Space target, TicketType type) {
        String oldKey = t.getKey();
        Workflow wf = target.workflowFor(type);
        Status status = wf.getStatuses().stream()
                .filter(s -> s.getName().equalsIgnoreCase(t.getStatus().getName())).findFirst()
                .orElse(wf.initialStatus());
        Space source = t.getSpace();
        t.setSpace(target);
        t.setNumber(target.takeNextNumber());
        t.setType(type);
        if (status.getCategory() == StatusCategory.DONE != t.isDone()) {
            t.setResolution(status.getCategory() == StatusCategory.DONE ? resolutions.findFirstByIsDefaultTrue().orElseThrow() : null);
            t.setResolvedAt(status.getCategory() == StatusCategory.DONE ? clock.instant() : null);
        }
        t.setStatus(status);
        t.setRank(Rank.between(tickets.maxRank(target.getId()).orElse(null), null));
        if (t.getAssignee() != null && !permissions.isAssignable(target, t.getAssignee().getId())) t.setAssignee(null);
        Set<Label> moved = new LinkedHashSet<>();
        for (Label l : t.getLabels()) {
            moved.add(labels.findByName(target.getId(), l.getName()).orElseGet(() -> labels.save(new Label(target, l.getName()))));
        }
        t.getLabels().clear();
        t.getLabels().addAll(moved);
        t.getComponents().clear(); // components and versions belong to the source space (listed as lost in the preview)
        t.getFixVersions().clear();
        tickets.flush();
        tickets.saveAlias(oldKey, t.getId());
        history.save(new HistoryEntry(t, users.getReferenceById(me.id()), "space", source.getId().toString(),
                target.getId().toString(), oldKey, t.getKey()));
    }

    private Space targetSpace(AuthUser me, Ticket t, String targetKey) {
        Space target = spaces.findByKey(targetKey).or(() -> spaces.findByAlias(targetKey))
                .orElseThrow(() -> Errors.notFound("Espace introuvable"));
        permissions.check(me, target, Perm.CREATE);
        if (target.getId().equals(t.getSpace().getId())) throw Errors.badRequest("SAME_SPACE", "Le ticket est déjà dans cet espace");
        return target;
    }

    public void delete(AuthUser me, String key, boolean withSubtasks) {
        Ticket t = get(me, key);
        permissions.check(me, t.getSpace(), Perm.DELETE);
        List<Ticket> subtasks = tickets.findChildren(t.getId()).stream()
                .filter(c -> c.getType().getHierarchyLevel() == -1).toList();
        if (!subtasks.isEmpty() && !withSubtasks) {
            throw Errors.of(HttpStatus.CONFLICT, "HAS_SUBTASKS", "Ce ticket a " + subtasks.size() + " sous-tâche(s) : confirmez leur suppression");
        }
        events.publishEvent(TicketEvent.of(TicketEvent.Type.DELETED, t, me.id()));
        tickets.deleteAll(subtasks);
        tickets.delete(t);
        audit.log(me.id(), "ticket.delete", t.getKey(), Map.of("summary", t.getSummary(), "subtasks", subtasks.size()));
    }

    // --- Defaults -------------------------------------------------------------

    public record DefaultValue(UUID ticketTypeId, String field, Object value) {}

    @Transactional(readOnly = true)
    public List<DefaultValue> getDefaults(Space space) {
        return defaults.findBySpace(space.getId()).stream()
                .map(d -> new DefaultValue(d.getTicketType() == null ? null : d.getTicketType().getId(), d.getField(), d.getValue()))
                .toList();
    }

    public void setDefaults(AuthUser me, Space space, List<DefaultValue> values) {
        permissions.check(me, space, Perm.ADMINISTER_SPACE);
        defaults.deleteBySpace(space.getId());
        defaults.flush();
        for (DefaultValue v : values) {
            if (!FIELDS.contains(v.field()) || v.field().equals("parent")) {
                throw Errors.badRequest("UNKNOWN_FIELD", "Champ sans valeur par défaut possible : " + v.field());
            }
            if (v.value() == null) continue;
            TicketType type = v.ticketTypeId() == null ? null : type(space, v.ticketTypeId().toString());
            defaults.save(new FieldDefault(space, type, v.field(), v.value()));
        }
    }

    /** Space-wide defaults, overridden by the ones for this ticket type. */
    private ObjectNode defaultsFor(Space space, TicketType type) {
        ObjectNode node = json.createObjectNode();
        List<FieldDefault> all = defaults.findBySpace(space.getId());
        all.stream().filter(d -> d.getTicketType() == null).forEach(d -> node.set(d.getField(), json.valueToTree(d.getValue())));
        if (type != null) {
            all.stream().filter(d -> d.getTicketType() != null && d.getTicketType().getId().equals(type.getId()))
                    .forEach(d -> node.set(d.getField(), json.valueToTree(d.getValue())));
        }
        return node;
    }

    // --- Helpers ----------------------------------------------------------------

    private void checkVersion(Ticket t, JsonNode body) {
        if (body.hasNonNull("version") && body.get("version").asInt() != t.getVersion()) throw conflict(t);
    }

    private static RuntimeException conflict(Ticket t) {
        var e = Errors.conflict("TICKET_CONFLICT", "Ce ticket a été modifié entre-temps par quelqu'un d'autre");
        e.getBody().setProperty("currentVersion", t.getVersion());
        return e;
    }

    private static void checkKnownFields(JsonNode body) {
        if (body == null || !body.isObject()) throw Errors.badRequest("INVALID_BODY", "Objet JSON attendu");
        for (String f : body.propertyNames()) {
            if (!FIELDS.contains(f) && !f.equals("version")) throw Errors.badRequest("UNKNOWN_FIELD", "Champ inconnu : " + f);
        }
    }

    private TicketType type(Space space, String id) {
        UUID typeId;
        try {
            typeId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw Errors.badRequest("INVALID_TYPE", "Type inconnu");
        }
        return space.getTicketTypes().stream().filter(ty -> ty.getId().equals(typeId)).findFirst()
                .orElseThrow(() -> Errors.badRequest("INVALID_TYPE", "Type non disponible dans cet espace"));
    }

    private static TicketType defaultType(Space space) {
        return space.getTicketTypes().stream().filter(ty -> ty.getName().equals("Tâche")).findFirst()
                .or(() -> space.getTicketTypes().stream().filter(ty -> ty.getHierarchyLevel() == 0).findFirst())
                .orElseThrow(() -> Errors.badRequest("NO_TYPE", "Aucun type de ticket disponible"));
    }

    private static String summary(JsonNode v) {
        String s = v == null || v.isNull() ? "" : v.asString().strip();
        if (s.isEmpty()) throw Errors.badRequest("SUMMARY_REQUIRED", "Le résumé est obligatoire");
        if (s.length() > 255) throw Errors.badRequest("SUMMARY_TOO_LONG", "Résumé limité à 255 caractères");
        if (s.contains("\n") || s.contains("\r")) throw Errors.badRequest("SUMMARY_MULTILINE", "Le résumé tient sur une ligne");
        return s;
    }

    private static UUID uuid(JsonNode v) {
        try {
            return UUID.fromString(v.asString());
        } catch (IllegalArgumentException e) {
            throw Errors.badRequest("INVALID_ID", "Identifiant invalide : " + v.asString());
        }
    }

    private static LocalDate date(JsonNode v) {
        if (v == null || v.isNull() || v.asString().isBlank()) return null;
        try {
            return LocalDate.parse(v.asString());
        } catch (DateTimeParseException e) {
            throw Errors.badRequest("INVALID_DATE", "Date invalide (AAAA-MM-JJ attendu)");
        }
    }

    private static List<String> stringList(JsonNode v) {
        if (!v.isArray()) throw Errors.badRequest("INVALID_LABELS", "Liste de labels attendue");
        List<String> out = new ArrayList<>();
        v.forEach(n -> out.add(n.asString()));
        return out;
    }
}
