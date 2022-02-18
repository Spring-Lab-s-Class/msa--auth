package com.justpickup.userservice.domain.jwt.service;

import com.justpickup.userservice.domain.user.dto.JwtTokenDto;

public interface RefreshTokenService {
    JwtTokenDto refreshJwtToken(String accessToken, String refreshToken);
}
