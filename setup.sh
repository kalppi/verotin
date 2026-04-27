#!/bin/bash
# Verotin local development setup script

set -e

echo "🚀 Verotin - Local Tax Deduction RAG System Setup"
echo "=================================================="
echo ""

# Check prerequisites
echo "✓ Checking prerequisites..."

if ! command -v docker &> /dev/null; then
    echo "❌ Docker is not installed. Please install Docker first."
    exit 1
fi

if ! command -v java &> /dev/null; then
    echo "❌ Java is not installed. Please install Java 21 or later."
    exit 1
fi

if ! command -v gradle &> /dev/null; then
    echo "❌ Gradle is not installed. Please install Gradle."
    exit 1
fi

if ! command -v ollama &> /dev/null; then
    echo "⚠️  Ollama is not in PATH. Make sure Ollama is installed and running."
    echo "   Visit: https://ollama.ai"
else
    echo "✓ Ollama found"
fi

JAVA_VERSION=$(java -version 2>&1 | grep -oP 'version "\K[^"]+')
echo "✓ Java version: $JAVA_VERSION"

echo ""
echo "📦 Starting PostgreSQL with pgvector..."
docker-compose up -d

# Wait for PostgreSQL to be ready
echo "⏳ Waiting for PostgreSQL to be ready..."
for i in {1..30}; do
    if docker exec verotin-postgres pg_isready -U verotin -d verotin &> /dev/null; then
        echo "✓ PostgreSQL is ready"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "❌ PostgreSQL failed to start"
        exit 1
    fi
    sleep 1
done

echo ""
echo "🛠️  Building project..."
gradle clean build -x test

echo ""
echo "✨ Setup Complete!"
echo ""
echo "🎯 Next steps:"
echo "1. Start Ollama: ollama serve"
echo "2. Pull models:"
echo "   ollama pull mxbai-embed-large"
echo "   ollama pull llama3"
echo "3. Run the app with fixture ingest:"
echo "   SPRING_PROFILES_ACTIVE=dev gradle bootRun"
echo "4. Open http://localhost:8080 to explore (no UI yet, use APIs)"
echo ""
echo "📚 API Examples:"
echo "  curl http://localhost:8080/api/documents"
echo "  curl http://localhost:8080/api/deductions"
echo "  curl 'http://localhost:8080/api/search/documents?query=office'​"
echo ""
echo "📖 See README.md for full documentation."
echo ""

