-- Owner-controlled feature switches (e.g. the gate access system and biometrics are optional).
CREATE TABLE app_settings (
    setting_key  varchar(60)  PRIMARY KEY,
    setting_value varchar(500) NOT NULL,
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    updated_by   bigint
);
