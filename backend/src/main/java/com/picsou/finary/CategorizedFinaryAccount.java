package com.picsou.finary;

import com.picsou.finary.dto.FinaryAccountDto;

/**
 * Keeps the original Finary category attached to an account payload so we don't
 * have to guess it later from a possibly non-unique account id.
 */
public record CategorizedFinaryAccount(
    String category,
    FinaryAccountDto account
) {}
