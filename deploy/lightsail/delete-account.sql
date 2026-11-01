-- Deletes one account and everything stored for it, by email (docs/deployment.md, "Deleting one account"). The Terms and
-- privacy page promises this. Run it from the repository root on your computer:
--   ssh -i ~/.ssh/schoolmatch-lightsail ubuntu@<server ip> 'cd /opt/schoolmatch && docker compose exec -T db psql -U schoolmatch -v ON_ERROR_STOP=1 -v email=someone@example.com' < deploy/lightsail/delete-account.sql
-- The output ends "DELETE 1" and "COMMIT"; "DELETE 0" means no account has that email.
-- Foreign keys (V1__user_tables.sql) remove the login sessions, profile with its CCA and programme choices, and
-- shortlist with its schools. Only choice_plan has no link to the account, so it goes first, through the shortlist;
-- its choice_plan_choice rows follow it.
BEGIN;
DELETE FROM choice_plan
WHERE id IN (SELECT s.choice_plan_id
             FROM shortlist s
             JOIN account a ON a.account_id = s.account_id
             WHERE a.email = LOWER(:'email'));
DELETE FROM account WHERE email = LOWER(:'email');
COMMIT;
