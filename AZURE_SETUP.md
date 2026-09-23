# Azure Document Intelligence – setup guide

This walks through everything on the Azure side that the batch job needs:
one **custom classifier** (decides the doc type) and one **custom extraction model per doc type**
(pulls the fields). The example is the `AUTO_PAY_AUTH` doc type (Automatic Payment Authorization Agreement).

> The job uses the Document Intelligence **v4.0 GA API (2024-11-30)** through the Java SDK
> `com.azure:azure-ai-documentintelligence`. Document Intelligence is now part of *Azure AI Foundry
> Tools*; the Studio and APIs below are the same.

---

## 0. What you will create

| Azure item | Example name | Used by the app as |
|---|---|---|
| Resource group | `rg-vision-ocr-dev` | – |
| Document Intelligence resource (S0) | `di-vision-ocr-dev` | `AZURE_DI_ENDPOINT`, `AZURE_DI_KEY` |
| Storage account + containers | `stvisionocrdev` / `train-autopay`, `train-classifier` | training data only |
| Custom extraction model (neural) | `autopay-neural-v1` | `doctype.auto-pay-auth.model-id` |
| Custom classifier | `vision-ocr-classifier-v1` | `AZURE_DI_CLASSIFIER_ID` |

Use a separate resource per environment (dev / test / prod). Train in dev, then **copy** the models
to prod (step 9) so prod never needs the training data.

---

## 1. Prerequisites and roles

* An Azure subscription, and permission to create resources (Contributor on the resource group).
* For you (the person labelling/training):
  * **Cognitive Services User** on the Document Intelligence resource
  * **Storage Blob Data Contributor** on the storage account
  * **Storage Account Contributor** once, to set CORS (step 3)
* For the batch application's identity (managed identity or service principal), if you don't use keys:
  * **Cognitive Services User** on the Document Intelligence resource

---

## 2. Create the Document Intelligence resource

Azure portal → *Create a resource* → **Document Intelligence** (listed under Azure AI services / Foundry Tools).

* **Pricing tier: Standard S0.** The free tier (F0) only handles small files (4 MB) and the first 2 pages
  of each document, which is too little for real scans.
* **Region:** pick one where *custom neural* models and *custom classifiers* are available, and that
  fits your data-residency rules. Check the Microsoft Learn page for Document Intelligence region support.
* Networking: start with *All networks* in dev. For prod, use a **private endpoint** and turn off
  public access.

After it's created, go to **Keys and Endpoint** and copy the endpoint URL (and a key, if you use keys).

CLI equivalent:

```bash
az group create -n rg-vision-ocr-dev -l eastus
az cognitiveservices account create -n di-vision-ocr-dev -g rg-vision-ocr-dev \
  --kind FormRecognizer --sku S0 -l eastus --custom-domain di-vision-ocr-dev --yes
az cognitiveservices account show -n di-vision-ocr-dev -g rg-vision-ocr-dev --query properties.endpoint
```

---

## 3. Create storage for the training data

1. Create a **Standard** StorageV2 account (e.g. `stvisionocrdev`).
2. Create containers:
   * `train-autopay`: filled AUTO_PAY_AUTH samples for the extraction model
   * `train-classifier`: one **folder per class** (see step 6)
3. **CORS** (Storage account → *Resource sharing (CORS)* → *Blob service*), so the Studio can read the files:

   | Setting | Value |
   |---|---|
   | Allowed origins | `https://documentintelligence.ai.azure.com` |
   | Allowed methods | select all |
   | Allowed headers | `*` |
   | Exposed headers | `*` |
   | Max age | `120` |

4. **Keep the containers private.** The training forms contain bank account and routing numbers.
   Microsoft's Studio quickstart shows a container access level of *Container*. For PII, keep access
   private and grant it through the roles in step 1, and confirm this with your security team.

---

## 4. Prepare training documents (AUTO_PAY_AUTH)

This is where your historical documents come in.

* Use **filled, scanned** forms (a blank form is not useful for training).
* Cover **every layout variant**: different lenders and form revisions, typed and handwritten,
  fax copies and phone photos. Aim for **at least 5 per variant**; 10–20 per variant gives better accuracy.
* Include some imperfect cases: missing fields, unsigned forms, skewed scans.
* Keep **10–20% of documents aside as a test set** that you never train on, and use it to measure accuracy.
* PDF, TIFF, JPEG and PNG are all supported. Keep each file under 500 MB (S0).
* If one file contains both copies of the agreement, you can keep it as is. The classifier splits it
  (split mode `auto`), and the job extracts the most confident copy.

Upload everything to `train-autopay` (Storage browser, Azure Storage Explorer, or `az storage blob upload-batch`).

---

## 5. Label and train the extraction model

1. Open **Document Intelligence Studio**: <https://documentintelligence.ai.azure.com/studio>
2. *Custom extraction model* → **Create a project**
   * Connect to your Document Intelligence resource and to the `train-autopay` container.
3. The Studio runs Layout OCR on every document. Then create these fields. **The names must match
   `azureField` in `doctypes/auto-pay-auth.xml` exactly:**

   | Field name | Field type | Where on the form |
   |---|---|---|
   | `CustomerName` | string | CUSTOMER NAME |
   | `LenderAccountNumber` | string |  ACCOUNT NUMBER |
   | `BuyerAddress` | string | Buyer Full Address |
   | `BankAccountHolderName` | string | Name on Bank Account |
   | `BankName` | string | FINANCIAL INSTITUTION NAME |
   | `RoutingNumber` | string | ABA/ROUTING NUMBER (9 digits) |
   | `CheckingAccountNumber` | string | PERSONAL CHECKING ACCOUNT NUMBER |
   | `Signature` | **signature** | SIGNATURE box |

4. Label every document: select the value's words, then click the field. Label only the value, not the
   printed caption. If a field is empty on a form, leave it unlabelled.
   * After you've labelled about 5 documents, train a first model and use **Auto label** with it on the
     rest. Then you only have to correct its labels.
   * **Using your historical data:** if you already know the correct values for these documents, you can
     generate the label files (`<file>.labels.json`) with a script instead of labelling by hand. The
     script runs Layout OCR and fuzzy-matches your known values to the OCR words. Review the result in
     the Studio before training.
5. **Train** → Model ID `autopay-neural-v1` → Build mode **Neural**.
   * Choose *Neural* because your layouts vary. *Template* is only better when the layout is fixed.
     If one variant keeps failing, you can train a template model for that variant and compose it with
     the neural model.
6. **Test** tab: run the documents from your test set. Check per-field confidence and correctness.
   These results tell you what to put for `minConfidence` in the doc type XML.

---

## 6. Train the classifier

The classifier needs **no labelling**: it learns from folders.

1. In `train-classifier`, create one folder per class. Use lower-case names, because these are the
   `classifierLabels` in the XML:
   ```
   auto_pay_auth/     <- all AUTO_PAY_AUTH variants (5+ per variant, max 100 per class)
   dispute_form/      <- later: one folder per future doc type
   other/             <- IMPORTANT: documents you do NOT want to process (letters, IDs, blank pages...)
   ```
   The `other` class keeps unrelated documents from being forced into a real doc type. The job sends
   `other` to REVIEW as `UNKNOWN_DOC_TYPE`.
2. Studio → *Custom classification model* → Create project → connect to the resource and the
   `train-classifier` container → create the classes from the folders.
3. **Train** → Classifier ID `vision-ocr-classifier-v1`.
4. Test with mixed files. The confidence scores tell you what to put for `minClassifyConfidence` per
   doc type (0.80 is set to start with).

You can also train both models without the Studio, using REST or the scripts in `scripts/`
(`documentModels:build` with `buildMode: neural`, and `documentClassifiers:build`).

---

## 7. Connect the batch application

Set these as environment variables (or in an external `application.properties` passed with
`-Dconfig.file=...`):

```bash
export AZURE_DI_ENDPOINT="https://di-vision-ocr-dev.cognitiveservices.azure.com/"
export AZURE_DI_KEY="<key>"                      # omit to use managed identity / az login
export AZURE_DI_CLASSIFIER_ID="vision-ocr-classifier-v1"
# model id per doc type:
java -Ddoctype.auto-pay-auth.model-id=autopay-neural-v1 -jar target/vision-ocr-batch.jar job-context.xml docExtractionJob -next
```

**Keyless (recommended for prod):** leave `AZURE_DI_KEY` empty. The app then uses
`DefaultAzureCredential`: the managed identity when running in Azure, or your `az login` locally.
Grant that identity **Cognitive Services User** on the Document Intelligence resource, and then
disable local (key) authentication on the resource.

---

## 8. Check accuracy against your historical data

1. Put the test-set files in `data/input/` and run the job.
2. Compare `doc_field` with the known values: field-level exact-match
   rate, and the review rate per reason.
3. Tune:
   * Many `LOW_CONFIDENCE:*` on correct values → lower that field's `minConfidence`.
   * Wrong values with high confidence → add training samples of that layout and retrain.
   * Routing checksum failures → usually handwriting. Add more handwritten samples.

---

## 9. Model lifecycle

* **Never overwrite a model id.** Train `autopay-neural-v2`, test it, then change
  `doctype.auto-pay-auth.model-id`. Rolling back is a config change.
* **Promote dev → prod** with the *Copy model* operation (`documentModels:authorizeCopy` on the target
  resource + `copyTo` on the source). Training data never needs to be in prod.
* **Review feedback:** documents corrected in the REVIEW queue are new labelled samples. Retrain
  periodically, especially when a new layout appears. A rising `UNKNOWN_DOC_TYPE` or
  `LOW_DOC_CONFIDENCE` rate is the signal to retrain.

---

## 10. Adding the next doc type (e.g. DISPUTE)

1. Collect and upload its samples → label → train `dispute-neural-v1` (steps 4–5).
2. Add a `dispute_form/` folder to the classifier data and retrain the classifier as
   `vision-ocr-classifier-v2` (step 6). Update `AZURE_DI_CLASSIFIER_ID`.
3. Copy `src/main/resources/doctypes/_doctype-template.xml.example` to `dispute.xml` and fill it in.
4. Build and run. No Java changes are needed.

---

## 11. Security and operations checklist

- [ ] Keys in Key Vault (or keyless with managed identity + local auth disabled)
- [ ] Private endpoint for Document Intelligence and Storage in prod
- [ ] Database encryption at rest (TDE / disk encryption) and restricted DB access
- [ ] Training containers private; access limited to the labelling team
- [ ] Diagnostic settings → Log Analytics (request counts, throttling, 429s)
- [ ] Check your resource's **transactions-per-second quota** and set `azure.concurrency` below it
      (the SDK retries 429 responses with back-off). Ask Microsoft for a quota increase if needed.
- [ ] Budget alert on the resource (pricing is per page, and custom extraction costs more than read/layout)
