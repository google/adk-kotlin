# LiteRT-LM Integration for Kotlin ADK

This package provides the
[LiteRtLmModel](src/commonJvmAndroidMain/kotlin/com/google/adk/kt/litertlm/LiteRtLmModel.kt)
implementation for the Google Agent Development Kit (ADK) in Kotlin, enabling
local on-device inference using LiteRT-LM.

[LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) is Google's
production-ready, high-performance, open-source inference framework designed to
efficiently run large language models on-device.

## Setup and Model Downloading

The easiest way to download models that can be run by LiteRT-LM is to use the
`litert-lm` CLI.

### 1. Install the LiteRT-LM CLI

Prerequisites: Python 3.10 or higher.

To install the CLI, run:

```bash
pip install --upgrade litert-lm
```

*(For alternative installation methods, such as using `uv`, refer to the
official
[LiteRT-LM CLI Installation Guide](https://developers.google.com/edge/litert-lm/cli/installation).)*

### 2. Download and Import Gemma 4 E2B

LiteRT-LM manages models via a local registry. You can import models directly
from Hugging Face:

```bash
litert-lm import \
  --from-huggingface-repo litert-community/gemma-4-E2B-it-litert-lm \
  gemma-4-E2B-it.litertlm \
  gemma4-e2b
```

Once downloaded, the model will be stored in the local registry at:

```
~/.litert-lm/models/gemma4-e2b/model.litertlm
```

For more CLI details and options, refer to the
[LiteRT-LM CLI Usage Guide](https://developers.google.com/edge/litert-lm/cli/usage).
