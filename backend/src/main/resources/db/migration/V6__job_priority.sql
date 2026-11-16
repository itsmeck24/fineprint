-- work a user is waiting on (a link they just saved, a board they just watched) runs before background polling.
-- extraction has no network step, so it also runs ahead of fetches waiting for the per-host gap.
alter table jobs add column priority smallint not null default 0;

drop index jobs_ready;
create index jobs_ready on jobs (priority desc, run_at) where status in ('ready', 'running');
