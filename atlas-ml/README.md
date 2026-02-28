# ATLAS-ML: Seismic Event Classification

**ATLAS-ML** is the machine learning component of the ATLAS (Applied Transforms, Learning & Analysis for Seismology) system. It provides automated classification of seismic signals using convolutional neural networks (CNNs), with a human-in-the-loop review process that allows the model to continuously improve over time.

---

## The Problem

Seismic monitoring systems generate continuous streams of signal data. Distinguishing genuine seismic events from noise — and classifying the type of noise when it occurs — is a critical and time-consuming task. Sources of false positives include drilling pipe noise, surface traffic, equipment malfunction, and environmental interference, all of which can mask or mimic real seismic events.

ATLAS-ML addresses this by:
- Automatically classifying incoming signals in near real-time
- Distinguishing seismic events from noise, and categorising both
- Learning from human reviewer corrections to improve accuracy over time
- Adapting to site-specific noise signatures as they are discovered in the field

---

## Classification Taxonomy

Rather than a flat list of classes, ATLAS-ML uses a two-tier classification structure that mirrors how a geophysicist actually thinks:

```
Tier 1: Is this a real seismic event?
│
├── YES → Tier 2: What kind?
│         ├── Earthquake (tectonic)
│         ├── Tremor / volcanic
│         ├── Induced seismicity (injection wells, etc.)
│         └── [extensible — new types added as needed]
│
└── NO  → Tier 2: What kind of noise or false positive?
          ├── Drilling pipe noise
          ├── Surface traffic / cultural noise
          ├── Equipment malfunction
          ├── Wind / environmental
          └── Unknown noise [flagged for human review and future classification]
```

The noise branch is treated as a first-class concern — not an afterthought. The system is specifically designed to grow its noise vocabulary over time as new interference sources are encountered in the field.

---

## System Architecture

ATLAS-ML operates in three modes:

### 1. Initial Training
A baseline model is built from a static, labeled dataset of seismic and noise signal images (spectrograms or waveform plots). This establishes the initial classifier before any live deployment.

### 2. Live Inference
Once deployed, the model watches an input stream for new signals. Each signal is classified and assigned a confidence score. Low-confidence results are flagged for priority review. All classifications are logged with a full audit trail.

### 3. Continuous Improvement (Human-in-the-Loop)
Classified signals are queued for human review. Reviewers confirm correct labels, correct wrong ones, and flag novel noise types as "unknown." Verified signals are added to the training pool. When the pool reaches a configurable threshold, the model is retrained — and benchmarked against the previous version before deployment.

```
[Signal Stream] → [CNN Classifier] → [Pending Review Queue]
                                              ↓
                                      [Human Reviewer]
                                      confirms / corrects / rejects
                                              ↓
                                      [Labeled Pool]
                                              ↓
                          [Retrain when threshold reached]
                                              ↓
                          [Benchmark vs. previous model]
                                              ↓
                                      [Deploy if improved]
```

---

## Technical Approach

- **Model architecture:** EfficientNet-B0 (pretrained on ImageNet, fine-tuned for seismic classification)
- **Input format:** Spectrogram or waveform images converted from raw seismic waveform files
- **Transfer learning:** Two-phase training — frozen backbone to train classifier head, then full fine-tuning at lower learning rate
- **Class imbalance:** Weighted random sampling and cost-sensitive loss to prevent bias toward majority classes (noise will vastly outnumber real events in deployment)
- **Confidence thresholding:** Predictions below a configurable threshold are flagged as uncertain rather than committed
- **Incremental retraining:** New verified images are incorporated without full retraining from scratch, with safeguards against catastrophic forgetting
- **Evaluation metrics:** Precision, recall, and F1-score per class — accuracy alone is insufficient given extreme class imbalance

---

## User Stories

### Epic 1: Initial Training
| ID | Story |
|----|-------|
| US-01 | As a data scientist, I want to point the system at a directory of labeled images and train a model, so that I have a baseline classifier to deploy |
| US-02 | As a data scientist, I want training metrics (accuracy, loss per class) saved to a run directory, so that I can evaluate model quality before deploying it |
| US-03 | As a data scientist, I want to version trained models with a timestamp and metadata, so that I can roll back if a new model performs worse |

### Epic 2: Inference on Incoming Signals
| ID | Story |
|----|-------|
| US-04 | As a system operator, I want the model to watch an input folder and classify each new image that appears, so that no manual triggering is required |
| US-05 | As a system operator, I want each classified image moved to a pending review folder with its predicted label and confidence score recorded, so that a human can act on it |
| US-06 | As a system operator, I want images below a confidence threshold to be flagged as uncertain, so that reviewers can prioritize those first |
| US-07 | As a system operator, I want a log of all classifications (filename, predicted class, confidence, timestamp), so that I have a full audit trail |

### Epic 3: Human Review
| ID | Story |
|----|-------|
| US-08 | As a reviewer, I want to see each pending image alongside its predicted label and confidence, so that I can quickly confirm or correct it |
| US-09 | As a reviewer, I want to correct a wrong label with a single action, so that the review process is fast |
| US-10 | As a reviewer, I want to reject an image entirely (e.g. poor quality, ambiguous), so that bad data doesn't pollute the training set |
| US-11 | As a reviewer, I want to see the confidence score, so that I know how much to trust the model's prediction before reviewing |

### Epic 4: Continuous Retraining
| ID | Story |
|----|-------|
| US-12 | As a data scientist, I want verified images to be automatically added to the labeled dataset after review, so that the training pool grows over time |
| US-13 | As a data scientist, I want the model to retrain when the verified pool reaches a configurable threshold, so that retraining is triggered automatically |
| US-14 | As a data scientist, I want the option to fine-tune the existing model on only the new verified images rather than retraining from scratch, so that retraining is faster |
| US-15 | As a data scientist, I want the new model to be benchmarked against the previous model before it replaces it, so that I don't accidentally deploy a worse model |

### Epic 5: Observability
| ID | Story |
|----|-------|
| US-16 | As a system operator, I want a dashboard showing images processed, pending review count, model version in use, and accuracy trend over time |
| US-17 | As a data scientist, I want to be alerted if model accuracy drops after a retraining cycle, so that data quality problems are caught early |

### Epic 6: Noise & False Positive Classification
| ID | Story |
|----|-------|
| US-18 | As a geophysicist, I want noise events classified by type rather than just labeled "not seismic," so that I can identify and mitigate specific interference sources |
| US-19 | As a geophysicist, I want to add new noise categories in the field without retraining from scratch, so that the system adapts to site-specific interference patterns |
| US-20 | As a reviewer, I want to flag a prediction as "new unknown noise type" when it doesn't fit any existing category, so that novel sources are captured for future training |
| US-21 | As a data scientist, I want the training dataset to maintain a deliberate balance between seismic and noise classes, so that the model doesn't develop a bias toward one or the other |

### Epic 7: False Positive Management
| ID | Story |
|----|-------|
| US-22 | As a geophysicist, I want every false positive to be automatically captured and flagged for review, so that nothing that fooled the model is silently discarded |
| US-23 | As a data scientist, I want a false positive rate tracked per noise class over time, so that I can see whether the model is improving at rejecting specific noise sources |
| US-24 | As a geophysicist, I want the system to learn site-specific noise signatures, so that the model can be adapted to a specific deployment environment |
| US-25 | As a system operator, I want a configurable sensitivity setting that trades off false positive rate against missed event rate, so that the system can be tuned to the risk tolerance of a given project |

---

## Open Questions

The following decisions need to be resolved before or during implementation:

### Data & Input
1. **What does the input data look like?** Raw waveform files (miniSEED, SAC)? Already-rendered spectrogram images? Or does ATLAS-ML need to own the image generation pipeline from raw waveform to spectrogram?
2. **What seismic instrumentation is in scope?** Geophones, broadband seismometers, DAS (distributed acoustic sensing)? Each has different signal characteristics.
3. **What time scales are relevant?** Microseismic (milliseconds), local earthquakes (seconds), teleseismic (minutes)? This affects spectrogram window size and resolution.

### Classification
4. **What seismic event types need to be distinguished?** Earthquake vs. noise is a binary problem; distinguishing earthquake vs. explosion vs. induced seismicity vs. tremor is significantly harder and requires more labeled data per class.
5. **What are the known false positive sources at target deployment sites?** Drilling pipe noise has been identified — are there others already known?

### Deployment
6. **Single site or multi-site deployment?** Multi-site deployment makes site-specific noise adaptation harder but more valuable.
7. **What triggers "new image arrival"?** A watched folder? An API endpoint? A camera or sensor writing files at a fixed interval?
8. **What is the retraining trigger?** Time-based (nightly), count-based (every N verified images), or manual?

### Review Workflow
9. **Who is the reviewer?** One person, a small team? Does the review UI need to be a web application, a desktop tool, or is a folder-based workflow sufficient to start?

### Incremental Learning
10. **How do we handle catastrophic forgetting?** When fine-tuning on new images only (US-14), the model risks forgetting previously learned classes. Approaches include replay buffers, elastic weight consolidation (EWC), or always including a sample of the original training data in each retraining cycle.

---

## Relationship to the Rest of ATLAS

ATLAS-ML is one component of the broader ATLAS system. Other components (managed separately) may include data acquisition, compression (see `seiszip`), visualisation, and reporting. ATLAS-ML is designed to be loosely coupled — it consumes image files from a watched directory and writes classifications and metadata to an output directory, with no hard dependency on the rest of the stack.

---

## Project Tracking

User stories are tracked as GitHub Issues on this repository, organised by epic using labels. The ATLAS-ML project board is maintained separately from other ATLAS components to keep stories from intermingling.

[View issues](../../issues) · [View project board](https://github.com/users/EdZilla/projects/1)
