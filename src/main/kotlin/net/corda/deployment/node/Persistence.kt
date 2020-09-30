package net.corda.deployment.node

interface Persistable<CURRENT, PERSISTABLE> {
    fun toPersistable(): PERSISTABLE
    fun fromPersistable(p: PERSISTABLE): CURRENT
}