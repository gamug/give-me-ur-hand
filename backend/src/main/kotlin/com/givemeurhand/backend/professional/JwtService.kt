package com.givemeurhand.backend.professional

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import java.time.Duration
import java.time.Instant
import java.util.Date

class JwtService(secret: String, private val expirationHours: Long = 12) {
    private val algorithm = Algorithm.HMAC256(secret)

    fun issue(professionalId: String): String {
        val now = Instant.now()
        return JWT.create()
            .withSubject(professionalId)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plus(Duration.ofHours(expirationHours))))
            .sign(algorithm)
    }

    fun verify(token: String): String? = try {
        JWT.require(algorithm).build().verify(token).subject
    } catch (e: JWTVerificationException) {
        null
    }
}
