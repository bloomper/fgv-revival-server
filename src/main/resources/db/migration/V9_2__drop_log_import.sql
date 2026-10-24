-- The Excel import of a decision log is removed. Rows already imported stay in the log, still marked IMPORT; only the
-- record of each import (file name, row count, warnings, who imported it) goes.

DROP TABLE IF EXISTS log_import_audit;
DROP TABLE IF EXISTS log_import;
