#!/bin/bash

BASE_URL="http://product-api:8080"
HEALTH_ENDPOINT="$BASE_URL/actuator/health"

echo "Esperando a que la API esté lista en $HEALTH_ENDPOINT..."

# Espera activa
sleep 5
until curl -s "$HEALTH_ENDPOINT" | grep UP > /dev/null; do
  echo "Esperando API..."
  sleep 5
done

# Create a product
echo "Creating product..."
PRODUCT_RESPONSE=$(curl -s -X POST "$BASE_URL/products" \
  -H "Content-Type: application/json" \
  -d '{"name":"Zapatillas deportivas","description":"Modelo 2025 edición limitada"}')

# Extract product ID from response (assuming the response contains an id field)
PRODUCT_ID=$(echo "$PRODUCT_RESPONSE" | grep -o '"id":[^,]*' | cut -d':' -f2 | tr -d '"' | tr -d ' ')

if [ -z "$PRODUCT_ID" ]; then
  echo "Error: Could not extract product ID from response"
  echo "Response: $PRODUCT_RESPONSE"
  exit 1
fi

echo "Product created with ID: $PRODUCT_ID"
echo -e "\n"

# Add first price (January to June 2024)
echo "Adding first price..."
curl -X POST "$BASE_URL/products/$PRODUCT_ID/prices" \
  -H "Content-Type: application/json" \
  -d '{"value":99.99,"initDate":"2024-01-01","endDate":"2024-06-30"}'
echo -e "\n"

# Add second price (July to December 2024)
echo "Adding second price..."
curl -X POST "$BASE_URL/products/$PRODUCT_ID/prices" \
  -H "Content-Type: application/json" \
  -d '{"value":129.99,"initDate":"2024-07-01","endDate":"2024-12-31"}'
echo -e "\n"

# Add third price (January 2025 onwards, no end date)
echo "Adding third price..."
curl -X POST "$BASE_URL/products/$PRODUCT_ID/prices" \
  -H "Content-Type: application/json" \
  -d '{"value":199.99,"initDate":"2025-01-01","endDate":null}'
echo -e "\n"

# Get the price on a specific date
DATE="2024-04-15"
echo "Getting price on date $DATE..."
curl -X GET "$BASE_URL/products/$PRODUCT_ID/prices?date=$DATE"
echo -e "\n"

# Get another price on a different date
DATE2="2024-08-15"
echo "Getting price on date $DATE2..."
curl -X GET "$BASE_URL/products/$PRODUCT_ID/prices?date=$DATE2"
echo -e "\n"

# Get current price
DATE3="2025-03-01"
echo "Getting current price on date $DATE3..."
curl -X GET "$BASE_URL/products/$PRODUCT_ID/prices?date=$DATE3"
echo -e "\n"

# Get full price history
echo "Getting full price history..."
curl -X GET "$BASE_URL/products/$PRODUCT_ID/prices"
echo -e "\n"

# Performance testing section
echo "===================="
echo "PERFORMANCE TESTING"
echo "===================="

# Test concurrent product creation
echo "Testing concurrent product creation..."
START_TIME=$(date +%s.%N)
for i in {1..1000}; do
  curl -s -X POST "$BASE_URL/products" \
    -H "Content-Type: application/json" \
    -d '{"name":"Producto Test '"$i"'","description":"Descripción del producto '"$i"'"}' &
done
wait
END_TIME=$(date +%s.%N)
DURATION=$(echo "$END_TIME - $START_TIME" | bc)
echo "1000 concurrent product creations took: $DURATION seconds"
echo -e "\n"

# Test concurrent price queries
echo "Testing concurrent price queries..."
START_TIME=$(date +%s.%N)
for i in {1..20000}; do
  curl -s -X GET "$BASE_URL/products/$PRODUCT_ID/prices?date=2024-04-15" > /dev/null &
done
wait
END_TIME=$(date +%s.%N)
DURATION=$(echo "$END_TIME - $START_TIME" | bc)
echo "20000 concurrent price queries took: $DURATION seconds"
echo -e "\n"

# Test concurrent price history requests
echo "Testing concurrent price history requests..."
START_TIME=$(date +%s.%N)
for i in {1..15000}; do
  curl -s -X GET "$BASE_URL/products/$PRODUCT_ID/prices" > /dev/null &
done
wait
END_TIME=$(date +%s.%N)
DURATION=$(echo "$END_TIME - $START_TIME" | bc)
echo "15000 concurrent price history requests took: $DURATION seconds"
echo -e "\n"

echo "Benchmark completed successfully!"