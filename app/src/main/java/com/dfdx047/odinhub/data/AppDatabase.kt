package com.dfdx047.odinhub.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [AppOverrideEntity::class],
    version = 8, // v5: limitMode + valores reais de TDP/Clock/Fan; v6: displayOverride + thermalMode; v7: m1Macro/m2Macro; v8: effClockKHz
    // Antes: exportSchema = false descartava o histórico de schema a cada build -- o plugin
    // `androidx.room` já estava configurado para gravar em `schemas/` (ver bloco `room {}`
    // abaixo), mas isso nunca gerava nada porque o export estava desligado aqui. Com
    // exportSchema = true, cada versão do banco fica registada em JSON dentro de `schemas/`,
    // permitindo ao Room validar migrações automaticamente em testes (MigrationTestHelper) e
    // deixando o histórico rastreável no controlo de versão -- importante antes de teres mais
    // utilizadores e migrações mais arriscadas de acertar às cegas.
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appOverrideDao(): AppOverrideDao

    companion object {
        /**
         * Antes desta auditoria, qualquer alteração de schema dependia de
         * `fallbackToDestructiveMigration()` (ver DatabaseModule), que apaga TODAS as regras
         * por jogo do utilizador a cada bump de versão. Esta migração preserva os dados já
         * existentes ao subir de v4 para v5: os perfis (nomes) continuam os mesmos, só ganham
         * colunas novas para os valores numéricos e o modo de limitação por jogo.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Lê as colunas que a tabela REALMENTE tem, em vez de assumir o formato da v4.
                //
                // Porquê: não existe schema exportado das versões 3 e 4 (o `exportSchema` estava
                // desligado até agora, e a pasta `schemas/` só tem 1.json e 2.json -- onde a
                // tabela ainda era outra coisa: controllerStyle/l2R2Style/perfMode/fanMode). Ou
                // seja, ninguém consegue verificar que forma tinha a v4. Um `ALTER TABLE ADD
                // COLUMN` para uma coluna que já existe falha, e uma coluna da entidade que
                // faltasse deixaria o schema diferente do esperado -- nesse caso o Room lança ao
                // abrir a base de dados, e o `fallbackToDestructiveMigration` NÃO salva, porque
                // só cobre saltos de versão sem migração, não uma migração que correu e deixou o
                // schema errado. Resultado prático: a app crashava ao abrir depois de atualizar.
                //
                // Adicionando apenas o que falta, esta migração funciona a partir de qualquer
                // variante da v4 e não depende de um histórico que não temos.
                val existing = mutableSetOf<String>()
                db.query("PRAGMA table_info(appoverride)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        existing += cursor.getString(nameIndex)
                    }
                }

                fun addIfMissing(column: String, definition: String) {
                    if (column !in existing) {
                        db.execSQL("ALTER TABLE appoverride ADD COLUMN $definition")
                    }
                }

                // Colunas introduzidas na v5 (valores numéricos reais + modo de limitação).
                addIfMissing("limitMode", "limitMode TEXT NOT NULL DEFAULT 'TDP'")
                addIfMissing("tdpWatts", "tdpWatts REAL")
                addIfMissing("perfClockKHz", "perfClockKHz INTEGER")
                addIfMissing("primeClockKHz", "primeClockKHz INTEGER")
                addIfMissing("gpuClockHz", "gpuClockHz INTEGER")
                addIfMissing("fanSettingsValue", "fanSettingsValue INTEGER")

                // Colunas que se assumia já existirem na v4. Se existirem, isto não faz nada; se
                // não existirem, é o que evita o crash descrito acima.
                addIfMissing("tdpProfile", "tdpProfile TEXT")
                addIfMissing("clockProfile", "clockProfile TEXT")
                addIfMissing("fanProfile", "fanProfile TEXT")
                addIfMissing("lsfgEnabled", "lsfgEnabled INTEGER NOT NULL DEFAULT 0")
                addIfMissing("lsfgMultiplier", "lsfgMultiplier INTEGER NOT NULL DEFAULT 2")
                addIfMissing("lsfgPerformanceMode", "lsfgPerformanceMode INTEGER NOT NULL DEFAULT 0")
                addIfMissing("lsfgFramePacing", "lsfgFramePacing INTEGER NOT NULL DEFAULT 1")
                addIfMissing("lsfgQuality", "lsfgQuality REAL NOT NULL DEFAULT 1.0")
                addIfMissing("sgsrEnabled", "sgsrEnabled INTEGER NOT NULL DEFAULT 0")
                addIfMissing("sgsrMode", "sgsrMode TEXT NOT NULL DEFAULT 'Quality'")
                addIfMissing("sgsrSharpness", "sgsrSharpness REAL NOT NULL DEFAULT 0.5")
                addIfMissing("reshadeProfile", "reshadeProfile TEXT NOT NULL DEFAULT 'Nenhum'")
                addIfMissing("saturationOverride", "saturationOverride REAL NOT NULL DEFAULT 1.0")
                addIfMissing("temperatureOverride", "temperatureOverride REAL NOT NULL DEFAULT 6500.0")
            }
        }

        /** v6: calibração de cor por jogo (interruptor) e limite térmico por jogo. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val existing = mutableSetOf<String>()
                db.query("PRAGMA table_info(appoverride)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) existing += cursor.getString(nameIndex)
                }
                if ("displayOverride" !in existing) db.execSQL("ALTER TABLE appoverride ADD COLUMN displayOverride INTEGER NOT NULL DEFAULT 0")
                if ("thermalMode" !in existing) db.execSQL("ALTER TABLE appoverride ADD COLUMN thermalMode TEXT")
            }
        }

        /** v7: macros de M1/M2 por jogo. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val existing = mutableSetOf<String>()
                db.query("PRAGMA table_info(appoverride)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) existing += cursor.getString(nameIndex)
                }
                if ("m1Macro" !in existing) db.execSQL("ALTER TABLE appoverride ADD COLUMN m1Macro TEXT")
                if ("m2Macro" !in existing) db.execSQL("ALTER TABLE appoverride ADD COLUMN m2Macro TEXT")
            }
        }

        /** v8: teto do cluster de eficiência por jogo. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val existing = mutableSetOf<String>()
                db.query("PRAGMA table_info(appoverride)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) existing += cursor.getString(nameIndex)
                }
                if ("effClockKHz" !in existing) db.execSQL("ALTER TABLE appoverride ADD COLUMN effClockKHz INTEGER")
            }
        }
    }
}