# Reserve Service Documentation

Welcome to the technical documentation for the Reserve Service microservice.

## Documentation Index

- [Design Decisions](design-decisions.md): Architectural rationales, isolation levels, atomic conditional updates, Redis cache versioning, transactional outbox, and lock ordering rules.
- [State Machine & Saga](state-machine.md): Reservation state lifecycle, terminal states, and Mermaid sequence diagram for the confirmation saga.
- [API & Postman Collection](postman/README.md): Postman collection, local environment setup, automated test scripts, and concurrency verification.

## Postman Deliverables

- [Collection JSON](postman/reserve.postman_collection.json)
- [Local Environment JSON](postman/local.postman_environment.json)
- [Postman README](postman/README.md)
