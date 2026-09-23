# Extra doc types

XML files in this folder are loaded as doc types by the batch job and the demo UI, in addition to the
ones built into the jar (`ocr-batch/src/main/resources/doctypes/`). The demo UI's designer
(*Configuration → New document type*) writes its files here.

* Folder: system property / environment variable `doctypes.dir` (default `./doctypes`, relative to the
  working directory).
* Same format as `ocr-batch/src/main/resources/doctypes/auto-pay-auth.xml`. A doc type name must not
  exist twice (built-in and here).
* To ship a doc type with the job, move its file into `ocr-batch/src/main/resources/doctypes/`.
