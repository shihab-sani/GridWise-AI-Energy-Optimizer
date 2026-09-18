# GridWise-AI-Energy-Optimizer


## 1. Overview
- Brief description of the solution.
- Architecture: LLM -> Guardrails -> OjAlgo Optimizer.

## 2. Prerequisites
- Java 21
- Maven
- Docker (for fallback--> https://hub.docker.com/repository/docker/shihabsani/gridwise-optimizer/general) 
- API Key for LLM Provider (e.g., Google Gemini)

## 3. Environment Variables
| Name | Description | Example |
|------|-------------|---------|
| `LLM_API_KEY` | API key for the LLM provider | `` |
| `LLM_API_URL` | OpenAI-compatible endpoint | `https://generativelanguage.googleapis.com/v1beta/openai/chat/completions` |
| `LLM_MODEL` | Model identifier | `gemini-3.6-flash` |

## 4. Local Quickstart (Copy-Paste)
```bash
# Clone the repo
git clone <your-repo-url>
cd GridWise-AI-Energy-Optimizer

# Set environment variables (Windows PowerShell)
$env:LLM_API_KEY="your-key"
$env:LLM_API_URL="your-url"
$env:LLM_MODEL="your-model"

# Run the application
./mvnw spring-boot:run
