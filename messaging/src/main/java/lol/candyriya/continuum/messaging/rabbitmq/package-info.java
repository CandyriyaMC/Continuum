/**
 * RabbitMQ transport: durable exchange, publisher confirms, manual acks, DLQ-backed retries.
 * Async events flow here; state is never authoritative in the broker.
 */
package lol.candyriya.continuum.messaging.rabbitmq;
