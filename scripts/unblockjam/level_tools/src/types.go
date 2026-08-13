package rush_conv

type Block struct {
	X      int  `json:"x"`
	Y      int  `json:"y"`
	Width  int  `json:"w"`
	Height int  `json:"h"`
	Fixed  bool `json:"fixed,omitempty"`
}

type Exit struct {
	X int `json:"x"`
	Y int `json:"y"`
}

type Board struct {
	Blocks []Block `json:"b"`
	Exit   Exit    `json:"e"`
	Width  int     `json:"w"`
	Height int     `json:"h"`
}

type Level struct {
	Board
	ID    string `json:"id"`
	Moves int    `json:"c"`
}