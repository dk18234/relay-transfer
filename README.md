# Relay — Resumable Dataset Transfers

A Java 21 / Spring Boot / PostgreSQL service for transferring research datasets in verified, restartable chunks. Local and S3 storage adapters share one durable manifest. A file becomes downloadable only after complete independent SHA-256 verification.

Implementation in progress; measured results will be added after actual verification. Built by Dinesh Kumar Rama with AI-assisted implementation.
