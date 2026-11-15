-- time from a posting being published to the collector first seeing it.
-- counts only postings published after polling of their board had started, and only when the poll before the
-- one that found it ran within 90 minutes, so laptop sleep and docker restarts do not count as slowness.
-- greenhouse first_published is the publish time of the job post. lever createdAt is when the posting was
-- created in lever, which can be earlier than when it went public, so lever is reported on its own.
-- run: docker compose exec -T db psql -U fineprint -d fineprint -f - < eval/detection-delay.sql
with first_poll as (
    select board_id, min(polled_at) as started from board_polls group by board_id
),
found as (
    select p.id, b.ats, p.first_published_at, p.first_seen_at,
           (select max(bp.polled_at) from board_polls bp
             where bp.board_id = p.board_id and bp.polled_at < p.first_seen_at - interval '1 second') as poll_before
    from postings p
    join boards b on b.id = p.board_id
    join first_poll f on f.board_id = p.board_id
    where p.first_published_at is not null
      and p.first_published_at > f.started
),
usable as (
    select ats, extract(epoch from first_seen_at - first_published_at) / 60.0 as delay_min
    from found
    where poll_before is not null and first_seen_at - poll_before <= interval '90 minutes'
)
select ats,
       count(*) as postings,
       round(percentile_cont(0.5) within group (order by delay_min)::numeric, 1) as median_min,
       round(percentile_cont(0.9) within group (order by delay_min)::numeric, 1) as p90_min,
       round(min(delay_min)::numeric, 1) as min_min,
       round(max(delay_min)::numeric, 1) as max_min
from usable group by ats order by ats;
