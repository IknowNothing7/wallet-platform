-- The merchant who created a hold; only they may capture or release it. Nullable because holds created before
-- this column have no recorded merchant: nobody can capture or release those, they just expire.

ALTER TABLE hold ADD COLUMN merchant_id BIGINT;

ALTER TABLE hold
    ADD CONSTRAINT fk_hold_merchant
        FOREIGN KEY (merchant_id)
        REFERENCES app_user (id) ON DELETE RESTRICT;
