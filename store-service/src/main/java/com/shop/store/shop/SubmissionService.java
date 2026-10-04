package com.shop.store.shop;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.util.UUID;
import static com.shop.store.shop.ShopModels.*;

/** Nontransactional acceptance boundary: a failed UNIQUE transaction completes rollback before winner recovery. */
@Service
public class SubmissionService {
    private final SubmissionStore store;
    private final ShopCodec codec;
    /** Receives a separate transactional bean so acceptance and recovery cannot accidentally share an aborted SQL transaction. */
    public SubmissionService(SubmissionStore store,ShopCodec codec) { this.store=store; this.codec=codec; }
    /**
     * Validates and accepts one request, returning only after commit. Concurrent same-key winners are read in
     * a fresh transaction; different fingerprints produce 409. Unrelated constraint failures remain storage errors.
     */
    public Submission submit(String scope,UUID cart,String key,SubmitInput input) {
        codec.identifier(scope); codec.idempotencyKey(key);
        if(input==null || input.expectedCartVersion()<0 || input.expectedCartVersion()>ShopCodec.MAX_VERSION)
            throw new ShopException(400,"VALIDATION_ERROR","Invalid expectedCartVersion");
        String fingerprint=codec.submissionFingerprint(scope,cart,input.expectedCartVersion());
        try { return store.accept(scope,cart,key,input.expectedCartVersion(),fingerprint); }
        catch(DuplicateKeyException conflict) {
            return store.replay(scope,key,fingerprint).orElseThrow(() -> conflict);
        }
    }
}
