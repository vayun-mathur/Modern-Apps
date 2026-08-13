package rush_conv

import (
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"slices"
	"sort"
)

const ID_VERSION = 1

func BoardID(board Board) string {
	blocks := slices.Clone(board.Blocks)

	sort.Slice(blocks, func(i, j int) bool {
		a, b := blocks[i], blocks[j]

		if a.X != b.X {
			return a.X < b.X
		}
		if a.Y != b.Y {
			return a.Y < b.Y
		}
		if a.Width != b.Width {
			return a.Width < b.Width
		}
		if a.Height != b.Height {
			return a.Height < b.Height
		}
		return !a.Fixed && b.Fixed
	})

	h := sha256.New()

	h.Write([]byte{
		byte(board.Width),
		byte(board.Height),
		byte(board.Exit.X),
		byte(board.Exit.Y),
	})

	for _, b := range blocks {
		h.Write([]byte{
			byte(b.X),
			byte(b.Y),
			byte(b.Width),
			byte(b.Height),
		})

		if b.Fixed {
			h.Write([]byte{1})
		} else {
			h.Write([]byte{0})
		}
	}

	sum := h.Sum(nil)
	return fmt.Sprintf("%d-%s", ID_VERSION, base64.RawURLEncoding.EncodeToString(sum[:12]))
}