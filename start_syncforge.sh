#!/bin/bash
# Stop any existing instance
pkill -f syncforge.jar
sleep 2

# Set environment variables
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/mft_db
export SPRING_DATASOURCE_USERNAME=mft_user
export SPRING_DATASOURCE_PASSWORD=Renoise28!
export SERVER_PORT=8081

# Run the app
nohup java -jar /app/syncforge/syncforge.jar > /app/syncforge/logs/server.log 2>&1 &
