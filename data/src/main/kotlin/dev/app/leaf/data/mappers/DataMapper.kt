package dev.app.leaf.data.mappers

interface DataMapper<Domain, Data> {
    fun toData(value: Domain): Data
    fun toDomain(value: Data): Domain
}