package dev.schembuilder.ui;

public final class Theme {
	private Theme() {}

	public static final int PANEL = 0xF20B0D12;
	public static final int CARD = 0xFF12151C;
	public static final int CARD_HI = 0xFF171B24;
	public static final int BORDER = 0xFF222733;
	public static final int ROW_HOVER = 0x14FFFFFF;

	public static final int TEXT = 0xFFECEEF4;
	public static final int MUTED = 0xFF8D93A3;
	public static final int FAINT = 0xFF5B6273;

	public static final int ACCENT = 0xFF5EEAD4;
	public static final int ACCENT_2 = 0xFF818CF8;
	public static final int GOOD = 0xFF4ADE80;
	public static final int WARN = 0xFFFBBF24;
	public static final int BAD = 0xFFF87171;

	public static final int BADGE_LITEMATIC = 0xFF60A5FA;
	public static final int BADGE_SCHEM = 0xFFF472B6;
	public static final int BADGE_NBT = 0xFFFBBF24;

	public static int badge(String format) {
		return switch (format) {
			case "LITEMATIC" -> BADGE_LITEMATIC;
			case "SCHEM" -> BADGE_SCHEM;
			default -> BADGE_NBT;
		};
	}
}
