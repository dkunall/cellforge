package com.cellforge.shared.security;

/**
 * Bearer tokens for calls between edge and cells, carrying the {@code cellforge.internal} scope.
 */
public interface ServiceTokens {

    String token();
}
