package org.uvo.uvostore.controller.settings;

import java.util.List;

public record ReorderRequest(List<Long> orderedIds) {
}
