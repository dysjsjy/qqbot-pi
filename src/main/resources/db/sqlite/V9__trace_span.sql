create table trace_span(
    id text primary key,
    run_id text not null,
    group_id text,
    parent_span text,
    kind text not null,
    name text not null,
    seq integer not null,
    status text not null,
    error_code text,
    start_us integer not null,
    duration_us integer not null,
    cpu_us integer not null,
    alloc_bytes integer not null,
    memory_bytes integer not null,
    estimated_input_tokens integer,
    input_tokens integer,
    output_tokens integer,
    cache_read_tokens integer,
    cache_write_tokens integer,
    reasoning_tokens integer,
    attributes text not null default '{}'
);
create index trace_span_run on trace_span(run_id,start_us);
create index trace_span_root on trace_span(start_us) where parent_span is null;
create index trace_span_group on trace_span(group_id);
