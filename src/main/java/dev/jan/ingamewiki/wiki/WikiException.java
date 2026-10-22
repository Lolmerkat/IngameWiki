package dev.jan.ingamewiki.wiki;

/**
 * A request to the wiki failed.
 */
public class WikiException extends RuntimeException {
	public WikiException(String message) {
		super(message);
	}

	public WikiException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * The requested page does not exist.
	 */
	public static final class Missing extends WikiException {
		private final String title;

		public Missing(String title) {
			super("There is no page called " + title);
			this.title = title;
		}

		public String title() {
			return title;
		}
	}
}
