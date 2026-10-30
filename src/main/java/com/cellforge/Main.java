package com.cellforge;

import com.cellforge.cell.CellApplication;
import com.cellforge.edge.EdgeApplication;

/**
 * One image, two roles. The edge holds the global tenant directory, routing, rate limits and billing; a cell
 * holds the tenants' data. The role comes from CELLFORGE_ROLE so compose can start both from the same image.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        String role = System.getenv().getOrDefault("CELLFORGE_ROLE", "");
        switch (role) {
            case "edge" -> EdgeApplication.main(args);
            case "cell" -> CellApplication.main(args);
            default -> throw new IllegalArgumentException("Set CELLFORGE_ROLE to edge or cell, not '" + role + "'");
        }
    }
}
