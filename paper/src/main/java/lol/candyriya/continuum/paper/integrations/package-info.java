/**
 * Optional, self-detecting integrations with other plugins. Everything in here is
 * invisible when its host is absent: editors in this package must degrade to a
 * logged no-op, never to a broken enable, and never to a network call that the
 * operator did not opt into.
 */
package lol.candyriya.continuum.paper.integrations;
