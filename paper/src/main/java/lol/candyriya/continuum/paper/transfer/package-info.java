/**
 * The backend side of a server switch: the pre-transfer capture half of the
 * transfer lifecycle. The arrival half runs through the join gate and the
 * restore pipeline.
 *
 * <p>The pre-transfer request itself arrives from the proxy via plugin
 * messaging once the messaging seam lands; this package owns the capture path
 * it will call, and that path is fully functional today.</p>
 */
package lol.candyriya.continuum.paper.transfer;
