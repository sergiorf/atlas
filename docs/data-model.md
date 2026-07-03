# Data model

Receita companies are root-level entities; establishments are branch/location-level entities. Atlas therefore preserves `cnpj_root`, `cnpj_branch`, `cnpj_check`, and `cnpj_full`. v0.1 bronze also preserves official establishment attributes plus `is_headquarters`, source provenance, source file, and ingestion timestamp. Municipality codes will be joined to reference metadata in a later silver stage.
