-- This is local panel metadata only. It does not issue any RouterOS request.
ALTER TABLE managed_port ADD COLUMN role TEXT;

-- Existing managed interfaces retain their prior visibility/configuration and
-- receive the least surprising presentation role. A WAN is deliberately never
-- inferred from an interface name during migration.
UPDATE managed_port
SET role = 'CLIENT'
WHERE role IS NULL;
