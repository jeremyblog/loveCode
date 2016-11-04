package io.github.jeremy.kindlejava;

import java.util.List;

abstract class ICaller extends ICall {

	public List<ICalled> calleds;

	public List<ICalled> getCalleds() {
		return calleds;
	}

	public void setCalleds(List<ICalled> calleds) {
		this.calleds = calleds;
	}
}
