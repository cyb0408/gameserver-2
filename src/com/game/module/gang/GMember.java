package com.game.module.gang;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工会成员
 * 
 * @author admin
 *
 */
public class GMember {

	private int playerId;
	private int position;// 职位
	private int contribute7;// 最近7天的总贡献
	private Map<Integer, Integer> donationRecord = new ConcurrentHashMap<Integer, Integer>();// 捐献次数记录
	private int taskContribution;
	private float trainingTime;//总的练功时间(单位小时)
	private long startTraining;//开始练功的时间
	private int challengeTimes; //挑战次数
	@JsonIgnore
	public int hp;
	@JsonIgnore
	public int hurt;
	@JsonIgnore
	public boolean bFight;
	public GMember() {
	}

	public GMember(int playerId,int challengeTimes) {
		this.playerId = playerId;
		this.challengeTimes = challengeTimes;
	}
	
	public GMember(int playerId, int position,int challengeTimes) {
		this.playerId = playerId;
		this.position = position;
		this.challengeTimes = challengeTimes;
	}

	public int getChallengeTimes() {
		return challengeTimes;
	}

	public void setChallengeTimes(int challengeTimes) {
		this.challengeTimes = challengeTimes;
	}

	public int getPlayerId() {
		return playerId;
	}

	public void setPlayerId(int playerId) {
		this.playerId = playerId;
	}

	public int getPosition() {
		return position;
	}

	public void setPosition(int position) {
		this.position = position;
	}

	public int getContribute7() {
		return contribute7;
	}

	public void setContribute7(int contribute) {
		this.contribute7 = contribute;
	}
	
	public void alterContribute7(int value){
		this.contribute7 += value;
	}

	public Map<Integer, Integer> getDonationRecord() {
		return donationRecord;
	}

	public void setDonationRecord(Map<Integer, Integer> donationRecord) {
		this.donationRecord = donationRecord;
	}

	public int getTaskContribution() {
		return taskContribution;
	}

	public void setTaskContribution(int taskContribution) {
		this.taskContribution = taskContribution;
	}
	
	public void alterTaskContribution(int value){
		this.taskContribution += value;
	}

	public float getTrainingTime() {
		return trainingTime;
	}

	public void setTrainingTime(float trainingTime) {
		this.trainingTime = trainingTime;
	}
	
	public void alterTrainingTime(float value){
		this.trainingTime += value;
	}

	public long getStartTraining() {
		return startTraining;
	}

	public void setStartTraining(long startTraining) {
		this.startTraining = startTraining;
	}
}
