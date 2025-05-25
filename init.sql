CREATE DATABASE IF NOT EXISTS wildfirechat DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE wildfirechat;

CREATE TABLE IF NOT EXISTS t_user_session (
  _uid VARCHAR(64) NOT NULL,
  _cid VARCHAR(64) NOT NULL,
  _secret VARCHAR(128) NOT NULL,
  PRIMARY KEY (_uid, _cid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4; 