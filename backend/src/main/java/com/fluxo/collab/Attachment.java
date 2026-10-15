package com.fluxo.collab;

import com.fluxo.ticket.Ticket;
import com.fluxo.user.User;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "attachments")
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User uploader;

    @Column(nullable = false)
    private String filename;

    @Column(nullable = false)
    private String mime;

    @Column(nullable = false)
    private long size;

    @Column(nullable = false)
    private String storageKey;

    private String thumbnailKey;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Attachment() {
    }

    public Attachment(Ticket ticket, User uploader, String filename, String mime, long size, String storageKey) {
        this.ticket = ticket;
        this.uploader = uploader;
        this.filename = filename;
        this.mime = mime;
        this.size = size;
        this.storageKey = storageKey;
    }

    public UUID getId() { return id; }
    public Ticket getTicket() { return ticket; }
    public User getUploader() { return uploader; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getMime() { return mime; }
    public long getSize() { return size; }
    public String getStorageKey() { return storageKey; }
    public String getThumbnailKey() { return thumbnailKey; }
    public void setThumbnailKey(String thumbnailKey) { this.thumbnailKey = thumbnailKey; }
    public Instant getCreatedAt() { return createdAt; }
}
