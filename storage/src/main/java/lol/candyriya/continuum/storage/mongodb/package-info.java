/**
 * MongoDB-based storage backed by the official sync driver with its own (non-Hikari) pool.
 * Atomic findOneAndUpdate-style operations carry the fencing-token checks.
 */
package lol.candyriya.continuum.storage.mongodb;
