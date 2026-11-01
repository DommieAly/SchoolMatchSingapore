-- Removes the PSLE score and posting group kept on one person's choice plan, by email (docs/deployment.md, "Clearing the
-- score from one choice plan"). The Terms and privacy page promises this. Run it from the repository root on your computer:
--   ssh -i ~/.ssh/schoolmatch-lightsail ubuntu@<server ip> 'cd /opt/schoolmatch && docker compose exec -T db psql -U schoolmatch -v ON_ERROR_STOP=1 -v email=someone@example.com' < deploy/lightsail/clear-plan-score.sql
-- The output is "BEGIN", "UPDATE 1" and "COMMIT"; "UPDATE 0" means that email has no choice plan.
-- The person must clear the score on their profile page first: while the profile has a score, ChoicePlanController.getPlan
-- copies it back the next time they open the plan.
BEGIN;
UPDATE choice_plan SET based_on_score = NULL, posting_group = NULL
WHERE id IN (SELECT s.choice_plan_id
             FROM shortlist s
             JOIN account a ON a.account_id = s.account_id
             WHERE a.email = LOWER(:'email'));
COMMIT;
