package io.github.hozdogus.pocketouya;

import java.util.*;

/** Read-only diagnostics. VERIFIED always names its verification scope, never implies gameplay. */
public final class CompatibilityEvidence {
    public enum Status {SUPPORTED, UNSUPPORTED, MALFORMED, SKIPPED, UNKNOWN, VERIFIED}
    public static final class Finding {
        public final Status status;
        public final String code, scope, detail;
        public final long address;
        public Finding(Status status,String code,String scope,String detail,long address){
            this.status=status;this.code=code;this.scope=scope;this.detail=detail;this.address=address;
        }
    }
    public static final class Candidate {
        public final long finalizer,target,thunk,atexitPlt;
        public Candidate(long finalizer,long target,long thunk,long atexitPlt){this.finalizer=finalizer;this.target=target;this.thunk=thunk;this.atexitPlt=atexitPlt;}
    }
    public final Status status;
    public final List<Finding> findings;
    public final List<Candidate> candidates;
    /** This prototype never authorizes or executes a transformation. */
    public final boolean transformationAuthorized=false;
    public CompatibilityEvidence(Status status,List<Finding> findings,List<Candidate> candidates){
        this.status=status;this.findings=Collections.unmodifiableList(new ArrayList<>(findings));this.candidates=Collections.unmodifiableList(new ArrayList<>(candidates));
    }
}
