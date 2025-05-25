package main

import (
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"log"
	"net/http"
	"os"
	"strconv"
	"time"

	_ "github.com/go-sql-driver/mysql"
)

type UserSession struct {
	UID    string
	CID    string
	Secret string
}

var db *sql.DB

func getTokenHandler(w http.ResponseWriter, r *http.Request) {
	userId := r.URL.Query().Get("userId")
	clientId := r.URL.Query().Get("clientId")
	timestamp := r.URL.Query().Get("timestamp")
	sign := r.URL.Query().Get("sign")

	secretKey := os.Getenv("GO_TOKEN_SECRET")
	if secretKey == "" {
		secretKey = "wfossgatewaydemo"
	}

	if userId == "" || clientId == "" || timestamp == "" || sign == "" {
		w.WriteHeader(http.StatusBadRequest)
		w.Write([]byte("missing params"))
		return
	}

	ts, err := strconv.ParseInt(timestamp, 10, 64)
	if err != nil {
		w.WriteHeader(http.StatusBadRequest)
		w.Write([]byte("invalid timestamp"))
		return
	}
	if abs(time.Now().Unix()-ts) > 1800 {
		w.WriteHeader(http.StatusForbidden)
		w.Write([]byte("timestamp expired"))
		return
	}

	expectedSign := calcSign(userId, clientId, timestamp, secretKey)
	if sign != expectedSign {
		w.WriteHeader(http.StatusForbidden)
		w.Write([]byte("invalid sign"))
		return
	}

	secret, err := queryUserSecret(userId, clientId)
	if err != nil {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte("db error"))
		return
	}
	if secret == "" {
		w.WriteHeader(http.StatusNotFound)
		w.Write([]byte("not found"))
		return
	}
	w.Write([]byte(secret))
}

func queryUserSecret(userId, clientId string) (string, error) {
	var secret string
	err := db.QueryRow("SELECT _secret FROM t_user_session WHERE _uid = ? AND _cid = ? LIMIT 1", userId, clientId).Scan(&secret)
	if err == sql.ErrNoRows {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	return secret, nil
}

func calcSign(userId, clientId, timestamp, key string) string {
	data := userId + clientId + timestamp + key
	h := sha256.New()
	h.Write([]byte(data))
	return hex.EncodeToString(h.Sum(nil))
}

func abs(x int64) int64 {
	if x < 0 {
		return -x
	}
	return x
}

func main() {
	mysqlDsn := os.Getenv("MYSQL_DSN")
	if mysqlDsn == "" {
		mysqlDsn = "root:root@tcp(mysql:3306)/wildfirechat?charset=utf8mb4&parseTime=True&loc=Local"
	}
	var err error
	db, err = sql.Open("mysql", mysqlDsn)
	if err != nil {
		log.Fatalf("failed to connect to db: %v", err)
	}
	if err = db.Ping(); err != nil {
		log.Fatalf("failed to ping db: %v", err)
	}

	http.HandleFunc("/getToken", getTokenHandler)
	log.Println("Go token service running on :2447 ...")
	log.Fatal(http.ListenAndServe(":2447", nil))
}
