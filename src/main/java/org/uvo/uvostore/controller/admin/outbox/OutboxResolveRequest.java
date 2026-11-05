package org.uvo.uvostore.controller.admin.outbox;

/** GAP-04. La nota se valida en {@code OutboxAdminService.resolve}. */
public record OutboxResolveRequest(String note) {
}
