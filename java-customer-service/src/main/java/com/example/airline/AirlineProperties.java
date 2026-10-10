package com.example.airline;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("airline")
public record AirlineProperties(
        @NotBlank String model,
        @Min(1) int maxModelSteps,
        @Min(1) int maxToolCalls,
        @Min(1) int maxHandoffs,
        @NotNull Duration turnTimeout,
        @NotNull Duration modelTimeout,
        @NotNull Duration sessionTtl,
        @Min(1) int maxSessions) {
    public AirlineProperties {
        if (turnTimeout != null && (turnTimeout.isNegative() || turnTimeout.isZero())
                || modelTimeout != null && (modelTimeout.isNegative() || modelTimeout.isZero())
                || sessionTtl != null && (sessionTtl.isNegative() || sessionTtl.isZero())) {
            throw new IllegalArgumentException("Timeouts must be positive");
        }
    }
}
