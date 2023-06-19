package stove.ktor.example.domain

import stove.ktor.example.LockProvider
import stove.ktor.example.UpdateJediRequest
import java.time.Duration

class JediService(private val repository: JediRepository, private val lockProvider: LockProvider) {
    suspend fun update(
        id: Long,
        request: UpdateJediRequest
    ) {
        val acquireLock = lockProvider.acquireLock(::JediService.name, Duration.ofSeconds(30))

        if (!acquireLock) {
            print("lock could not be acquired")
            return
        }

        try {
            repository.transaction {
                val jedi = it.findById(id)
                jedi.name = request.name
                it.update(jedi)
            }
        } finally {
            lockProvider.releaseLock(::JediService.name)
        }
    }
}
