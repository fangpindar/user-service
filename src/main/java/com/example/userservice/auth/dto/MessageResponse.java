package com.example.userservice.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Generic message response")
public record MessageResponse(String message) {}
