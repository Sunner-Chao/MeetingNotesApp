# Server Knowledge Layout

The server knowledge area separates document layout from retrievable facts.

## Directories

- `templates/`: Versioned DOCX files, slot metadata, rendering rules, and template manifests.
- `documents/`: Future private RAG source documents after access control, chunking, and retention policies are implemented.

## Retrieval Flow

1. Retrieve authorized facts from the RAG document index.
2. Ask the selected Agent to produce structured meeting-minute data.
3. Validate the structured fields against the selected template slot schema.
4. Render the fields and meeting images into the registered DOCX template.

Template binaries are not embedded into the vector index. Their manifests and descriptions may be indexed so an Agent can select a suitable template.
