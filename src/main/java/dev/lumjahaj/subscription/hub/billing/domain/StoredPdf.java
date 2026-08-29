package dev.lumjahaj.subscription.hub.billing.domain;

import java.io.InputStream;

/**
 * A stored PDF opened for reading, paired with its size.
 *
 * The length comes back from the object store's own metadata rather than
 * being derived by reading the stream, so the controller can set a real
 * Content-Length header without first buffering the whole file into
 * memory just to measure it.
 */
public record StoredPdf(InputStream content, long contentLength) {
}
