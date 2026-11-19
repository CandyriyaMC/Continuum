package lol.candyriya.continuum.paper.modules.custom;

import lol.candyriya.continuum.api.module.ModuleInfo;
import lol.candyriya.continuum.api.module.SyncAdapter;

import java.lang.reflect.Type;

/**
 * One registered adapter and everything the registry pre-computed about it at registration
 * time: metadata (as the API reports it), the resolved payload type T for
 * encoding/decoding. Resolved once — never per operation.
 *
 * <p>Package-private on purpose: the composition root passes first-party metadata in via
 * {@link FirstPartyModule}, it never gets this type back.</p>
 */
record RegisteredAdapter(SyncAdapter<?, ?> adapter,
                         Type payloadType,
                         ModuleInfo moduleInfo,
                         AdapterRegistry.Registration registration) {

    String id() {
        return adapter.id();
    }
}
