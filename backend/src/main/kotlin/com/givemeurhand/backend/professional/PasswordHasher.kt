package com.givemeurhand.backend.professional

import org.mindrot.jbcrypt.BCrypt

object PasswordHasher {
    fun hash(plain: String): String = BCrypt.hashpw(plain, BCrypt.gensalt())
    fun verify(plain: String, hashed: String): Boolean = BCrypt.checkpw(plain, hashed)
}
