package com.devoxx.data.vote.voters;

import com.afollestad.materialdialogs.MaterialDialog;
import com.annimon.stream.Optional;
import com.devoxx.BuildConfig;
import com.devoxx.R;
import com.devoxx.connection.ApiException;
import com.devoxx.connection.model.ErrorMessageModel;
import com.devoxx.connection.model.SlotApiModel;
import com.devoxx.connection.vote.VoteApi;
import com.devoxx.connection.vote.VoteConnection;
import com.devoxx.connection.vote.model.VoteApiModel;
import com.devoxx.connection.vote.model.VoteApiSimpleModel;
import com.devoxx.connection.vote.model.VoteDetailsApiModel;
import com.devoxx.data.RealmProvider;
import com.devoxx.data.conference.ConferenceManager;
import com.devoxx.data.model.RealmConference;
import com.devoxx.data.user.UserManager;
import com.devoxx.data.vote.VotedTalkModel;
import com.devoxx.data.vote.interfaces.IOnVoteForTalkListener;
import com.devoxx.data.vote.interfaces.ITalkVoter;
import com.devoxx.utils.Logger;
import com.google.gson.Gson;

import org.androidannotations.annotations.Background;
import org.androidannotations.annotations.Bean;
import org.androidannotations.annotations.EBean;
import org.androidannotations.annotations.UiThread;
import org.joda.time.DateTime;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.v4.graphics.drawable.DrawableCompat;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.RatingBar;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.realm.Realm;
import retrofit2.Call;
import retrofit2.Response;

@EBean
public class TalkVoter implements ITalkVoter {

	private static final int NOT_READY_VOTE_HTTP_CODE = 500;
	private static final int ALREADY_VOTED_HTTP_CODE = 409;

	@Bean
	VoteConnection voteConnection;

	@Bean
	RealmProvider realmProvider;

	@Bean
	ConferenceManager conferenceManager;

	@Bean
	UserManager userManager;

	@Override
	public void showVoteDialog(Context context, SlotApiModel slot, IOnVoteForTalkListener listener) {
		final MaterialDialog.Builder builder = new MaterialDialog.Builder(context);
		final MaterialDialog dialog = builder
				.customView(R.layout.talk_rating_layout, true)
				.positiveText(R.string.vote)
				.callback(new MaterialDialog.ButtonCallback() {
					@Override
					public void onPositive(MaterialDialog dialog) {
						super.onPositive(dialog);
						final View customView = dialog.getCustomView();
						final RatingBar ratingBar = (RatingBar) customView.findViewById(R.id.talkRatingBar);
						final int rating = (int) ratingBar.getRating();

						final String content = extractText(customView, R.id.talkRatingContentFeedback);
						final String delivery = extractText(customView, R.id.talkRatingDeliveryRemarks);
						final String other = extractText(customView, R.id.talkRatingOther);

						voteForTalk(rating, slot.talk.id, listener, content, delivery, other);
					}

					@NonNull private String extractText(View customView, int inputId) {
						return ((EditText) customView.
								findViewById(inputId)).getText().toString();
					}
				})
				.build();
		dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
		dialog.show();

		final View customView = dialog.getCustomView();
		final RatingBar ratingBar = (RatingBar) customView.findViewById(R.id.talkRatingBar);
		if (android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
			DrawableCompat.setTint(ratingBar.getProgressDrawable(), Color.parseColor("#E3B505"));
		}

		final TextView title = (TextView) customView.findViewById(R.id.talkRatingTitle);
		title.setText(slot.talk.title);

		final TextView speakers = (TextView) customView.findViewById(R.id.talkRatingSpeakers);
		speakers.setText(slot.talk.getReadableSpeakers());
	}

	@Override public boolean canVoteForTalk(SlotApiModel slotModel) {
		final DateTime now = new DateTime(ConferenceManager.getNow());
		final DateTime talkStart = new DateTime(slotModel.fromTimeMillis);
		return now.isAfter(talkStart);
	}

	@Override
	public boolean isVotingEnabled() {
		final Optional<RealmConference> conferenceOptional = conferenceManager.getActiveConference();
		final RealmConference realmConference = conferenceOptional.orElse(null);
		final boolean isConferenceAvailable = realmConference != null;
		boolean isVoteEnabled = isConferenceAvailable
				&& Boolean.parseBoolean(realmConference.getVotingEnabled());

		if (isConferenceAvailable) {
			final String fromDate = realmConference.getFromDate();
			final String toDate = realmConference.getToDate();
			final DateTime fromConfDate = ConferenceManager.parseConfDate(fromDate);
			final DateTime toConfDate = ConferenceManager.parseConfDate(toDate);
			final DateTime now = new DateTime(ConferenceManager.getNow());

			isVoteEnabled &= now.isAfter(fromConfDate) && now.isBefore(toConfDate);
		}

		return isVoteEnabled;
	}

	@Override
	public boolean isAlreadyVoted(String talkId) {
		final Realm realm = realmProvider.getRealm();
		final VotedTalkModel model = realm.where(VotedTalkModel.class)
				.equalTo("talkId", talkId).findFirst();
		realm.close();
		return model != null;
	}

	private String getConfCode() {
		return conferenceManager.getActiveConferenceId().get();
	}

	@Background
	protected void voteForTalk(int rating, String talkId, IOnVoteForTalkListener listener, String content, String delivery, String other) {
		if (BuildConfig.DEBUG) {
			doFakeCall(rating, talkId, listener, content, delivery, other);
		} else {
			doRealCall(rating, talkId, listener, content, delivery, other);
		}
	}

	private void doFakeCall(int rating, String talkId, IOnVoteForTalkListener listener, String content, String delivery, String other) {
		final boolean success = System.currentTimeMillis() % 2 == 0;
		if (success) {
			final Realm realm = realmProvider.getRealm();
			rememberVote(realm, talkId);
			realm.close();
			notifyAboutSuccess(listener);
		} else {
			notifyAboutError(listener, new ApiException(new ErrorMessageModel("Vote call error!")));
		}
	}

	private void doRealCall(int rating, String talkId, IOnVoteForTalkListener listener, String content, String delivery, String other) {
		final Realm realm = realmProvider.getRealm();
		try {
			final VoteApi voteApi = voteConnection.getVoteApi();
			final Call<VoteApiSimpleModel> call = createRequest(rating, talkId, content, delivery, other, voteApi);
			final Response<VoteApiSimpleModel> response = call.execute();

			if (response.isSuccessful()) {
				rememberVote(realm, talkId);
				notifyAboutSuccess(listener);
			} else if (response.code() == NOT_READY_VOTE_HTTP_CODE) {
				notifyAboutCantVote(listener);
			} else if (response.code() == ALREADY_VOTED_HTTP_CODE) {
				rememberVote(realm, talkId);
				notifyAboutCantVoteMore(listener);
			} else {
				final String errorMessage = response.errorBody().string();
				final Gson gson = new Gson();
				final ErrorMessageModel errorMessageModel = gson.fromJson(errorMessage, ErrorMessageModel.class);
				notifyAboutError(listener, new ApiException(errorMessageModel));
			}
		} catch (IOException e) {
			Logger.exc(e);
			notifyAboutError(listener, e);
		} finally {
			realm.close();
		}
	}

	private void rememberVote(Realm realm, String talkId) {
		realm.beginTransaction();
		realm.copyToRealm(new VotedTalkModel(talkId));
		realm.commitTransaction();
	}

	@UiThread void notifyAboutSuccess(IOnVoteForTalkListener listener) {
		if (listener != null) {
			listener.onVoteForTalkSucceed();
		}
	}

	@UiThread void notifyAboutError(IOnVoteForTalkListener listener, Exception e) {
		if (listener != null) {
			listener.onVoteForTalkFailed(e);
		}
	}

	@UiThread void notifyAboutCantVote(IOnVoteForTalkListener listener) {
		if (listener != null) {
			listener.onCantVoteOnTalkYet();
		}
	}

	@UiThread void notifyAboutCantVoteMore(IOnVoteForTalkListener listener) {
		if (listener != null) {
			listener.onCantVoteMoreThanOnce();
		}
	}

	private Call<VoteApiSimpleModel> createRequest(int rating, String talkId, String content, String delivery, String other, VoteApi voteApi) {
		final String userId = userManager.getUserCode();
		if (TextUtils.isEmpty(content) && TextUtils.isEmpty(delivery) && TextUtils.isEmpty(other)) {
			return voteApi.vote(getConfCode(), new VoteApiSimpleModel(talkId, rating, userId));
		} else {
			final List<VoteDetailsApiModel> details = new ArrayList<>();
			appendDetails(details, "Content", content, rating);
			appendDetails(details, "Delivery", delivery, rating);
			appendDetails(details, "Other", other, rating);
			final VoteApiModel model = new VoteApiModel(talkId, userId, details);
			return voteApi.vote(getConfCode(), model);
		}
	}

	private void appendDetails(List<VoteDetailsApiModel> result, String key, String value, int rating) {
		if (!TextUtils.isEmpty(value)) {
			result.add(new VoteDetailsApiModel(key, rating, value));
		}
	}
}
