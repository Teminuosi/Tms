package net

import "testing"

func TestNormalizeWildcardAddr(t *testing.T) {
	cases := map[string]string{
		"[::]:20001":     ":20001",
		":20001":         ":20001",
		"0.0.0.0:20001":  "0.0.0.0:20001",
		"[::1]:20001":    "[::1]:20001",
		"[fd00::1]:2000": "[fd00::1]:2000",
		"":               "",
	}
	for in, want := range cases {
		if got := NormalizeWildcardAddr(in); got != want {
			t.Errorf("%q: got %q want %q", in, got, want)
		}
	}
}
