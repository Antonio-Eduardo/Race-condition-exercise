CREATE EXTENSION IF NOT EXISTS btree_gist;

INSERT INTO usuario (nome, cpf) VALUES ('sistema', '00000000000');

DO $$
  DECLARE
      sistema_id BIGINT := (SELECT id FROM usuario WHERE cpf = '00000000000');
      atual RECORD;
  BEGIN
      FOR atual IN
          SELECT id, laboratorio_id, inicio, fim, criado_em FROM reserva
          WHERE status = 'AGENDADA'
          ORDER BY criado_em, id
      LOOP
          IF EXISTS (
              SELECT 1 FROM reserva anterior
              WHERE anterior.laboratorio_id = atual.laboratorio_id
                AND anterior.status = 'AGENDADA'
                AND (anterior.criado_em, anterior.id) < (atual.criado_em, atual.id)
                AND anterior.inicio < atual.fim
                AND anterior.fim > atual.inicio
          ) THEN
              UPDATE reserva
              SET status = 'CANCELADA', cancelado_em = now(), cancelado_por = sistema_id
              WHERE id = atual.id;
          END IF;
      END LOOP;
  END $$;

ALTER TABLE  reserva ADD CONSTRAINT  reserva_sem_sobreposicao
    EXCLUDE USING  gist (laboratorio_id WITH =, tstzrange(inicio,fim, '[)') WITH &&)
    WHERE ( status = 'AGENDADA');