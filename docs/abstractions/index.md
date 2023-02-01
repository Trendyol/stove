# Abstractions

There are abstractions that every component needs to implement when it joins to the framework.

For example, Couchbase implements `DatabaseSystem`, it is an interface that defines the database operations for
the e2e testings. Every physical database dependency or `Pluggable Test System` for a database needs to implement that
interface.
