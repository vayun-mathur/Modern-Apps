package rush_conv

import (
	"github.com/fogleman/rush"
)

func tocoord(position int, board_size int) (int, int) {
	x := position % board_size
	y := board_size - 1 - (position / board_size)
	return x, y
}

func tosize(size int, orientation int) (int, int) {
	if orientation == 0 {
		return size, 1
	}
	return 1, size
}

func Convert(board_desc string) Board {
	board, _ := rush.NewBoardFromString(board_desc)

	blocks := make([]Block, 0, len(board.Pieces)+len(board.Walls))

	for _, piece := range board.Pieces {
		x, y := tocoord(piece.Position, board.Width)
		if piece.Orientation == 1 {
			y -= piece.Size - 1
		}
		w, h := tosize(piece.Size, int(piece.Orientation))
		blocks = append(blocks, Block{
			X:      x,
			Y:      y,
			Width:  w,
			Height: h,
		})
	}
	for _, wall := range board.Walls {
		x, y := tocoord(wall, board.Width)
		blocks = append(blocks, Block{
			X:      x,
			Y:      y,
			Width:  1,
			Height: 1,
			Fixed:  true,
		})
	}

	return Board{
		Blocks: blocks,
		Exit: Exit{
			X: board.Width,
			Y: board.Height / 2,
		},
		Width:  board.Width,
		Height: board.Height,
	}
}