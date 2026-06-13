# so-vits-svc Training Tutorial for Sayuri Android App

A complete guide to train a **so-vits-svc** voice conversion model on anime character voice samples and deploy it to your Sayuri Android app for fully local, on-device speech synthesis.

**Target**: RTX 4050 Laptop GPU with 6GB VRAM  
**Dataset**: 10–30 minutes of anime character audio  
**Output**: TorchScript model for Android + local audio playback

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Part 1: Data Collection](#part-1-data-collection)
3. [Part 2: Environment Setup](#part-2-environment-setup)
4. [Part 3: Data Preprocessing](#part-3-data-preprocessing)
5. [Part 4: Training Configuration](#part-4-training-configuration)
6. [Part 5: Train the Model](#part-5-train-the-model)
7. [Part 6: Export to TorchScript](#part-6-export-to-torchscript)
8. [Part 7: Android Integration](#part-7-android-integration)
9. [Troubleshooting](#troubleshooting)

---

## Prerequisites

### Hardware
- NVIDIA RTX 4050 (or similar GPU with ≥6GB VRAM)
- 20+ GB free disk space (for model, data, and checkpoints)
- 30–60 minutes for training on 6GB GPU

### Software
- Python 3.8+
- CUDA Toolkit 11.8+ (installed and working)
- ffmpeg (for audio extraction)
- Git

### Installation check
```powershell
# Check Python
python --version

# Check CUDA
nvidia-smi

# Check ffmpeg
ffmpeg -version

# If missing, install via pip/chocolatey:
# pip install --upgrade pip
# choco install ffmpeg (or download from ffmpeg.org)
```

---

## Part 1: Data Collection

### Step 1.1: Choose your anime character

Pick **one character** you want to clone. Examples:
- Rem (Re:Zero)
- Miku Nakano (Quintessential Quintuplets)
- Asuna (Sword Art Online)
- Any anime character with distinct, clear voice

**Why one character?** Consistency improves model quality.

### Step 1.2: Find and download anime clips

**Option A: YouTube**
```powershell
# Install yt-dlp if you haven't
pip install yt-dlp

# Create working directory
mkdir anime_voice_training
cd anime_voice_training
mkdir raw_clips

# Download a clip (replace URL)
yt-dlp "https://www.youtube.com/watch?v=..." -o "raw_clips/clip_01.mp4"
yt-dlp "https://www.youtube.com/watch?v=..." -o "raw_clips/clip_02.mp4"
# ... repeat for 20–40 clips
```

**Option B: Local anime files**
```powershell
# If you have anime files locally, copy them
Copy-Item "C:\Anime\series.mkv" "raw_clips\episode_01.mkv"
```

**Target**: Collect 20–40 video clips containing your character speaking (total 10–30 minutes).

### Step 1.3: Extract audio from all clips

```powershell
# Navigate to working directory
cd anime_voice_training

# Batch extract audio from all videos
foreach ($file in Get-ChildItem raw_clips -Include *.mp4, *.mkv, *.webm) {
    $output = "raw_clips/$($file.BaseName).wav"
    Write-Host "Extracting: $($file.Name) → $output"
    ffmpeg -i $file.FullName -q:a 0 -map a $output
}
```

You now have `.wav` files in `raw_clips/`.

### Step 1.4: Manual cleanup (optional but recommended)

Use **Audacity** or `ffmpeg` to:
- Remove long silence at start/end
- Trim pauses between sentences
- Normalize loudness to -20dB

```powershell
# Simple normalization with ffmpeg
foreach ($file in Get-ChildItem raw_clips -Filter *.wav) {
    ffmpeg -i $file.FullName -af "loudnorm" "normalized/$($file.Name)"
}
```

---

## Part 2: Environment Setup

### Step 2.1: Clone so-vits-svc

```powershell
cd anime_voice_training

# Clone the official repo (or the popular fork)
git clone https://github.com/voicevox/so-vits-svc.git
cd so-vits-svc
```

Or use the alternative fork:
```powershell
git clone https://github.com/svc-develop-team/so-vits-svc.git
cd so-vits-svc
```

### Step 2.2: Create a virtual environment

```powershell
# Create venv
python -m venv venv

# Activate venv
.\venv\Scripts\Activate.ps1

# Upgrade pip
python -m pip install --upgrade pip
```

### Step 2.3: Install dependencies

```powershell
# Install PyTorch with CUDA support
pip install torch torchaudio torchvision --index-url https://download.pytorch.org/whl/cu118

# Install so-vits-svc requirements
pip install -r requirements.txt

# Additional dependencies
pip install librosa scipy numpy matplotlib pyyaml
```

**Verify PyTorch uses GPU:**
```powershell
python -c "import torch; print(f'CUDA available: {torch.cuda.is_available()}'); print(f'GPU: {torch.cuda.get_device_name(0)}')"
```

Expected output:
```
CUDA available: True
GPU: NVIDIA GeForce RTX 4050
```

---

## Part 3: Data Preprocessing

### Step 3.1: Organize your audio files

Create the structure:
```
so-vits-svc/
├── raw/
│   └── character_name/
│       ├── 01_scene.wav
│       ├── 02_dialogue.wav
│       ├── 03_emotion.wav
│       └── ... (20–40 files)
├── dataset_raw/
├── dataset/
└── so_vits_svc_models/
    └── character_name/
```

```powershell
# Copy normalized WAVs to raw folder
mkdir raw/character_name
Copy-Item "raw_clips/*.wav" "raw/character_name/"
```

### Step 3.2: Resample audio to 44.1 kHz

```powershell
# This standardizes sample rate for training
python resample.py --in_dir raw --out_dir dataset_raw
```

**Output**: `dataset_raw/character_name/` with resampled `.wav` files.

### Step 3.3: Extract features

```powershell
# Extract mel-spectrograms and compute durations
python preprocess.py --training_prefix dataset_raw --val_percent 0.1
```

**Output**: `dataset/44k/character_name/` with:
- `*.mel.pt` (mel-spectrogram features)
- `*.pit.pt` (pitch information)
- `*.length.pt` (duration info)

---

## Part 4: Training Configuration

### Step 4.1: Create hparams.yaml for 6GB GPU

Create or edit `hparams.yaml`:

```yaml
###########################################################
#                HYPERPARAMETERS                         #
#              Optimized for RTX 4050 (6GB)              #
###########################################################

# Model architecture (reduced for 6GB GPU)
hidden_channels: 192          # 256 → 192
filter_channels: 768          # 1024 → 768
n_heads: 2                    # 4 → 2
n_layers: 4                   # 6 → 4
kernel_size: 3
p_dropout: 0.1
resblock: "1"
resblock_kernel_sizes: [3, 7, 11]
resblock_dilation_sizes: [[1, 3, 5], [1, 3, 5], [1, 3, 5]]
upsample_rates: [8, 8, 2, 2]
upsample_initial_channel: 512
upsample_kernel_sizes: [16, 16, 4, 4]

# Encoder
use_spectral_norm: false

# Audio
sampling_rate: 44100
hop_length: 512
win_length: 2048
f_min: 40
f_max: 7600
f_pmin: 40
f_pmax: 7600
n_fft: 2048
n_mel: 80
eps: 1e-9
mel_fmin: 0.0
mel_fmax: null
add_blank: true

# Training (critical for 6GB GPU)
batch_size: 6                 # Reduce to 4 if OOM
num_workers: 0                # Use 0 to avoid multiprocessing overhead
learning_rate: 0.0002
betas: [0.8, 0.99]
eps: 1e-9
weight_decay: 0.0
grad_clip: 1.0
warmup_epochs: 0.0
epochs: 100
save_interval: 10             # Save checkpoint every 10 epochs
eval_interval: 10
seed: 1234

# Loss weights
lambda_kl: 1.0
lambda_align: 15.0
lambda_feat: 1.0
lambda_mel: 45.0
lambda_dur: 1.0
lambda_f0: 1.0
lambda_voiced: 1.0

# Slice length (reduce if OOM)
slice_len: 32000              # Reduce from 16000 if needed

# Device
device: "cuda"
fp16: false                   # Disable mixed precision if unstable

###########################################################
```

### Step 4.2: Adjust if you get OOM

If training crashes with "out of memory":

```yaml
# Option 1: Reduce batch size
batch_size: 4  # Was 6

# Option 2: Reduce slice length
slice_len: 16000  # Was 32000

# Option 3: Further reduce model size
hidden_channels: 128          # Was 192
filter_channels: 512          # Was 768
n_layers: 3                   # Was 4
```

---

## Part 5: Train the Model

### Step 5.1: Start training

```powershell
# Activate venv if not already active
.\venv\Scripts\Activate.ps1

# Navigate to so-vits-svc directory
cd so-vits-svc

# Start training
python train.py -c hparams.yaml -m so_vits_svc_models/character_name
```

**First run warning**: First epoch may take longer as PyTorch optimizes the CUDA kernels.

### Step 5.2: Monitor training

In a **separate PowerShell window**, watch GPU memory:

```powershell
# Monitor GPU usage every 1 second
nvidia-smi --query-gpu=index,name,memory.used,memory.total,utilization.gpu,utilization.memory --format=csv,nounits,noheader -l 1
```

**Expected**:
- GPU memory: 4.5–5.5 GB (batch_size=6)
- GPU utilization: 85–95%
- Training time: 30 mins – 2 hours (100 epochs)

### Step 5.3: Checkpoints

Checkpoints are saved in:
```
so-vits-svc_models/character_name/
├── G_0.pth          (epoch 0)
├── G_10.pth         (epoch 10)
├── G_20.pth         (epoch 20)
└── ...
```

**Pick the best checkpoint**: Usually the latest (G_100.pth or highest epoch), but you can test earlier ones.

### Step 5.4: Reduce training time (optional)

If 100 epochs is too long, train fewer:

```powershell
# Edit hparams.yaml
# epochs: 30  (much faster, 10–15 mins)
# Then retrain

python train.py -c hparams.yaml -m so_vits_svc_models/character_name
```

**Trade-off**: Fewer epochs = faster training, but lower quality. 50–100 epochs is a good balance.

---

## Part 6: Export to TorchScript

### Step 6.1: Create export script

Create `export_mobile.py`:

```python
import torch
import sys
from models import SynthesizerTrn
from utils import get_hparams_from_file

# Config
config_path = "hparams.yaml"
model_path = "so_vits_svc_models/character_name/G_100.pth"  # Latest checkpoint
output_path = "so_vits_svc_mobile.pt"

# Load config and model
hparams = get_hparams_from_file(config_path)
model = SynthesizerTrn(
    len(hparams.symbols),
    hparams.inter_channels,
    hparams.hidden_channels,
    hparams.filter_channels,
    hparams.n_heads,
    hparams.n_layers,
    hparams.kernel_size,
    hparams.p_dropout,
    hparams.resblock,
    hparams.resblock_kernel_sizes,
    hparams.resblock_dilation_sizes,
    hparams.upsample_rates,
    hparams.upsample_initial_channel,
    hparams.upsample_kernel_sizes,
    hparams.gin_channels,
)

state_dict = torch.load(model_path, map_location="cpu")
model.load_state_dict(state_dict["model"])
model.eval()

# Export to TorchScript
print("Exporting to TorchScript...")
example_input = (
    torch.randint(0, len(hparams.symbols), (1, 100), dtype=torch.long),  # phone_ids
    torch.randint(0, 1, (1,), dtype=torch.long),                          # speaker_id
    torch.randn(1, 100),                                                   # F0 (pitch)
    torch.tensor([1.0]),                                                   # duration_scale
)

traced = torch.jit.trace(model, example_input)
traced.save(output_path)

print(f"✓ Model exported to {output_path}")
print(f"  File size: {os.path.getsize(output_path) / 1024 / 1024:.2f} MB")
```

### Step 6.2: Run export

```powershell
python export_mobile.py
```

**Output**: `so_vits_svc_mobile.pt` (~50–200 MB depending on model size)

### Step 6.3: Export phoneme vocabulary

Create `export_phonemes.py`:

```python
import json
from utils import get_hparams_from_file

config_path = "hparams.yaml"
hparams = get_hparams_from_file(config_path)

phoneme_vocab = {symbol: idx for idx, symbol in enumerate(hparams.symbols)}

with open("phoneme_vocab.json", "w", encoding="utf-8") as f:
    json.dump(phoneme_vocab, f, ensure_ascii=False, indent=2)

print(f"✓ Phoneme vocab exported: {len(phoneme_vocab)} symbols")
print(f"  Symbols: {list(phoneme_vocab.keys())[:20]}")
```

**Output**: `phoneme_vocab.json` with phoneme→ID mapping

---

## Part 7: Android Integration

### Step 7.1: Copy model files to Android assets

```powershell
# From so-vits-svc directory
Copy-Item "so_vits_svc_mobile.pt" "..\..\..\app\src\main\assets\"
Copy-Item "phoneme_vocab.json" "..\..\..\app\src\main\assets\"
```

Verify:
```
app/src/main/assets/
├── so_vits_svc_mobile.pt
└── phoneme_vocab.json
```

### Step 7.2: Add PyTorch Mobile to Gradle

Edit `app/build.gradle.kts`:

```kotlin
dependencies {
    // ... existing dependencies ...
    
    // PyTorch Mobile
    implementation("org.pytorch:pytorch_android_lite:2.0.0")
    implementation("org.pytorch:pytorch_android_torchvision_lite:2.0.0")
}
```

### Step 7.3: Create VitsWrapper.kt

Create `app/src/main/java/com/example/sayuri/audio/VitsWrapper.kt`:

```kotlin
package com.example.sayuri.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioManager
import com.example.sayuri.model.TtsResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import kotlin.coroutines.resume

/**
 * VITS wrapper for local, on-device anime voice synthesis.
 * Uses a trained so-vits-svc model exported to TorchScript.
 */
class VitsWrapper(private val context: Context) {
    private var module: Module? = null
    private var phonemeVocab: Map<String, Int> = emptyMap()
    private val sampleRate = 44100

    // ── Initialization ────────────────────────────────────────────────────────────

    suspend fun initialize(): Boolean = withContext(Dispatchers.Default) {
        return try {
            // Load TorchScript model
            val modelPath = assetFilePath(context, "so_vits_svc_mobile.pt")
            module = LiteModuleLoader.load(modelPath)

            // Load phoneme vocabulary
            val vocabJson = context.assets.open("phoneme_vocab.json")
                .bufferedReader()
                .use { it.readText() }
            phonemeVocab = parsePhonemeVocab(vocabJson)

            true
        } catch (e: Exception) {
            android.util.Log.e("VitsWrapper", "Initialization failed", e)
            false
        }
    }

    // ── Speaking ──────────────────────────────────────────────────────────────────

    suspend fun speak(text: String): TtsResult = withContext(Dispatchers.Default) {
        val model = module ?: return@withContext TtsResult.Error("VITS not initialized")

        return@withContext try {
            // Convert text to phoneme IDs
            val phoneIds = textToPhonemeIds(text)
            if (phoneIds.isEmpty()) {
                return@withContext TtsResult.Error("Could not convert text to phonemes")
            }

            // Prepare input tensors
            val phoneTensor = Tensor.fromBlob(
                phoneIds.toLongArray(),
                longArrayOf(1, phoneIds.size.toLong())
            )
            val speakerIdTensor = Tensor.fromBlob(longArrayOf(0), longArrayOf(1))
            val durationScaleTensor = Tensor.fromBlob(floatArrayOf(1.0f), longArrayOf(1))

            // Run inference
            val outputs = model.forward(
                IValue.from(phoneTensor),
                IValue.from(speakerIdTensor),
                IValue.from(durationScaleTensor)
            ).toTensor()

            // Convert output to audio
            val audioData = outputs.dataAsFloatArray()
            if (audioData.isEmpty()) {
                return@withContext TtsResult.Error("Model produced empty output")
            }

            // Play audio
            playAudio(audioData)
            TtsResult.Done
        } catch (e: Exception) {
            android.util.Log.e("VitsWrapper", "Speak failed", e)
            TtsResult.Error("VITS inference failed: ${e.message}")
        }
    }

    /**
     * Stops current playback.
     */
    fun stop() {
        // Implement if needed
    }

    /**
     * Releases all resources.
     */
    fun shutdown() {
        module = null
        phonemeVocab = emptyMap()
    }

    // ── Private helpers ───────────────────────────────────────────────────────────

    private fun textToPhonemeIds(text: String): List<Int> {
        // Simple phoneme conversion
        // In production, use a real phoneme converter or pre-tokenized input
        return text.lowercase()
            .split(Regex("\\s+"))
            .flatMap { word ->
                word.map { char ->
                    phonemeVocab[char.toString()] ?: phonemeVocab["unk"] ?: 0
                }
            }
    }

    private fun parsePhonemeVocab(json: String): Map<String, Int> {
        // Parse JSON phoneme vocabulary
        val map = mutableMapOf<String, Int>()
        val lines = json.trim('{', '}').split(',')
        for (line in lines) {
            val parts = line.split(':')
            if (parts.size == 2) {
                val key = parts[0].trim().trim('"')
                val value = parts[1].trim().toIntOrNull() ?: continue
                map[key] = value
            }
        }
        return map
    }

    private fun playAudio(floatAudio: FloatArray) {
        // Convert float audio to PCM16
        val pcmAudio = FloatArray(floatAudio.size) { i ->
            (floatAudio[i] * 32767).toInt().toShort().toInt().toFloat() / 32767
        }

        // Create AudioTrack
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            floatAudio.size * 2,  // bytes
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        // Convert float to PCM16
        val shortAudio = ShortArray(floatAudio.size) { i ->
            (floatAudio[i] * 32767).toShort()
        }

        track.play()
        track.write(shortAudio, 0, shortAudio.size)
        
        // Block until done
        while (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            Thread.sleep(100)
        }
        
        track.stop()
        track.release()
    }

    private fun assetFilePath(context: Context, assetName: String): String {
        val file = File(context.filesDir, assetName)
        if (!file.exists()) {
            context.assets.open(assetName).use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return file.absolutePath
    }
}
```

### Step 7.4: Update VoiceAssistantViewModel

Replace `TtsWrapper` with `VitsWrapper` in your ViewModel:

```kotlin
// In VoiceAssistantViewModelFactory.kt or MainActivity.kt

// OLD:
// val tts = TtsWrapper(context)

// NEW:
val tts = VitsWrapper(context)
```

### Step 7.5: Update MainActivity permissions

Ensure `RECORD_AUDIO` is in `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
```

---

## Troubleshooting

### Issue 1: Training crashes with OOM
**Solution**:
```yaml
# In hparams.yaml, reduce:
batch_size: 4  # Was 6
slice_len: 16000  # Was 32000
hidden_channels: 128  # Was 192
```

### Issue 2: Poor audio quality
**Causes**:
- Too little training data (collect 20+ minutes)
- Too few epochs (train for ≥50 epochs)
- Bad input audio (clean with Audacity)

**Solution**: Re-collect cleaner data, train longer.

### Issue 3: Audio extraction with yt-dlp fails
```powershell
# Update yt-dlp
pip install --upgrade yt-dlp
```

### Issue 4: PyTorch CUDA not found
```powershell
# Reinstall PyTorch with CUDA support
pip uninstall torch torchaudio
pip install torch torchaudio --index-url https://download.pytorch.org/whl/cu118
```

### Issue 5: Android app crashes on model load
**Check**:
- Model file exists: `app/src/main/assets/so_vits_svc_mobile.pt`
- Phoneme vocab exists: `app/src/main/assets/phoneme_vocab.json`
- PyTorch dependency in Gradle
- Read assets permissions in Manifest

---

## Summary Checklist

- [ ] Collected 20–40 anime video clips (10–30 min total)
- [ ] Extracted audio with `ffmpeg`
- [ ] Set up Python environment and so-vits-svc
- [ ] Resampled data to 44.1 kHz
- [ ] Preprocessed features
- [ ] Configured `hparams.yaml` for 6GB GPU
- [ ] Trained model for 50–100 epochs
- [ ] Exported to TorchScript
- [ ] Copied model files to Android assets
- [ ] Added PyTorch Mobile dependency
- [ ] Implemented `VitsWrapper.kt`
- [ ] Updated ViewModel to use `VitsWrapper`
- [ ] Tested on device

---

## Resources

- **so-vits-svc**: https://github.com/voicevox/so-vits-svc
- **PyTorch Mobile**: https://pytorch.org/mobile/home/
- **yt-dlp**: https://github.com/yt-dlp/yt-dlp
- **Audacity**: https://www.audacityteam.org/

---

## Support

If you encounter issues:
1. Check error logs: `adb logcat | grep VitsWrapper`
2. Review so-vits-svc GitHub issues
3. Test PyTorch on PC first before Android
4. Verify CUDA is working: `nvidia-smi`

Good luck training your anime voice model! 🎤✨
