package com.fluxo.workflow;

import java.util.UUID;

/** « Envoyer un webhook » post-transition action (spec §4.9); delivered after commit by the webhook module. */
public record WorkflowWebhook(String url, String ticketKey, String transition, UUID actorId) {}
