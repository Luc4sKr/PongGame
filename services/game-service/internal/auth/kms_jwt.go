package auth

import (
	"context"
	"crypto/rsa"
	"crypto/x509"
	"errors"
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/kms"
	"github.com/golang-jwt/jwt/v5"
	"github.com/ponggame/game-service/internal/config"
)

type PlayerClaims struct {
	PlayerID string
	Username string
}

type Verifier struct {
	kmsClient   *kms.Client
	kmsKeyID    string
	issuer      string
	audience    string
	cacheTTL    time.Duration
	mu          sync.RWMutex
	cachedKey   *rsa.PublicKey
	cachedUntil time.Time
}

func NewVerifier(ctx context.Context, cfg config.Config) (*Verifier, error) {
	opts := []func(*awsconfig.LoadOptions) error{
		awsconfig.WithRegion(cfg.AWSRegion),
	}
	if cfg.AWSEndpointURL != "" {
		opts = append(opts, awsconfig.WithCredentialsProvider(
			credentials.NewStaticCredentialsProvider(cfg.AWSAccessKeyID, cfg.AWSSecretAccessKey, ""),
		))
	}

	awsCfg, err := awsconfig.LoadDefaultConfig(ctx, opts...)
	if err != nil {
		return nil, fmt.Errorf("load aws config: %w", err)
	}

	kmsClient := kms.NewFromConfig(awsCfg, func(o *kms.Options) {
		if cfg.AWSEndpointURL != "" {
			o.BaseEndpoint = aws.String(cfg.AWSEndpointURL)
		}
	})

	v := &Verifier{
		kmsClient: kmsClient,
		kmsKeyID:  cfg.KMSKeyID,
		issuer:    cfg.JWTIssuer,
		audience:  cfg.JWTAudience,
		cacheTTL:  time.Duration(cfg.KMSCacheTTLSeconds) * time.Second,
	}

	// Warm up public key cache with retries in case Terraform is still applying
	var lastErr error
	for attempt := 1; attempt <= 20; attempt++ {
		if _, err := v.refreshKey(ctx, true); err == nil {
			return v, nil
		} else {
			lastErr = err
			log.Printf("[kms] waiting for KMS key %s (attempt %d/20): %v", cfg.KMSKeyID, attempt, err)
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-time.After(time.Second):
			}
		}
	}
	return nil, fmt.Errorf("failed to load KMS public key %s: %w", cfg.KMSKeyID, lastErr)
}

func (v *Verifier) Verify(ctx context.Context, rawToken string) (*PlayerClaims, error) {
	key, err := v.getKey(ctx)
	if err != nil {
		return nil, err
	}

	claims, err := v.parseAndValidate(rawToken, key)
	if err == nil {
		return claims, nil
	}

	// Retry once with refreshed KMS public key in case of key rotation
	if refreshed, rErr := v.refreshKey(ctx, true); rErr == nil {
		if retryClaims, retryErr := v.parseAndValidate(rawToken, refreshed); retryErr == nil {
			return retryClaims, nil
		}
	}
	return nil, err
}

func (v *Verifier) parseAndValidate(rawToken string, key *rsa.PublicKey) (*PlayerClaims, error) {
	parsed, err := jwt.Parse(rawToken, func(t *jwt.Token) (any, error) {
		if _, ok := t.Method.(*jwt.SigningMethodRSA); !ok || t.Method.Alg() != jwt.SigningMethodRS256.Alg() {
			return nil, fmt.Errorf("unexpected signing method: %v", t.Header["alg"])
		}
		return key, nil
	},
		jwt.WithIssuer(v.issuer),
		jwt.WithAudience(v.audience),
		jwt.WithLeeway(5*time.Second),
	)
	if err != nil {
		return nil, err
	}
	mapClaims, ok := parsed.Claims.(jwt.MapClaims)
	if !ok || !parsed.Valid {
		return nil, errors.New("invalid jwt claims")
	}
	sub, _ := mapClaims.GetSubject()
	username, _ := mapClaims["preferred_username"].(string)
	if sub == "" || username == "" {
		return nil, errors.New("missing sub or preferred_username claim")
	}
	return &PlayerClaims{PlayerID: sub, Username: username}, nil
}

func (v *Verifier) getKey(ctx context.Context) (*rsa.PublicKey, error) {
	v.mu.RLock()
	if v.cachedKey != nil && time.Now().Before(v.cachedUntil) {
		k := v.cachedKey
		v.mu.RUnlock()
		return k, nil
	}
	v.mu.RUnlock()
	return v.refreshKey(ctx, false)
}

func (v *Verifier) refreshKey(ctx context.Context, force bool) (*rsa.PublicKey, error) {
	v.mu.Lock()
	defer v.mu.Unlock()

	if !force && v.cachedKey != nil && time.Now().Before(v.cachedUntil) {
		return v.cachedKey, nil
	}

	resp, err := v.kmsClient.GetPublicKey(ctx, &kms.GetPublicKeyInput{
		KeyId: aws.String(v.kmsKeyID),
	})
	if err != nil {
		return nil, err
	}
	pubAny, err := x509.ParsePKIXPublicKey(resp.PublicKey)
	if err != nil {
		return nil, fmt.Errorf("parse SPKI public key: %w", err)
	}
	rsaPub, ok := pubAny.(*rsa.PublicKey)
	if !ok {
		return nil, errors.New("KMS key is not an RSA public key")
	}

	v.cachedKey = rsaPub
	v.cachedUntil = time.Now().Add(v.cacheTTL)
	log.Printf("[kms] fetched and cached RSA public key from KMS key %s (TTL=%s)", v.kmsKeyID, v.cacheTTL)
	return rsaPub, nil
}
