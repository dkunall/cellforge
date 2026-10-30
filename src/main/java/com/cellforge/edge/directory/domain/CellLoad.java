package com.cellforge.edge.directory.domain;

public record CellLoad(String cellId, String internalUrl, boolean accepting, long tenants) {
}
